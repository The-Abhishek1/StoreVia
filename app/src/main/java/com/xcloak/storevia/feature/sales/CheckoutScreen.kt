package com.xcloak.storevia.feature.sales

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.database.dao.ProductListRow
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.HeldCartEntity
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.feature.stock.FieldError

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CheckoutScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onCreateProduct: (String) -> Unit,
    onCompleted: (String) -> Unit,
    vm: CheckoutViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val customers by vm.customers.collectAsStateWithLifecycle()
    val held by vm.heldCarts.collectAsStateWithLifecycle()

    val canDiscount = can(Permission.APPLY_DISCOUNT)
    val canPrice = can(Permission.OVERRIDE_PRICE)
    val canCredit = can(Permission.SELL_ON_CREDIT)
    val canOverrideBatch = can(Permission.OVERRIDE_BATCH)
    val canManageCustomers = can(Permission.MANAGE_CUSTOMERS)
    var qtyFor by remember { mutableStateOf<CartLine?>(null) }
    var discountFor by remember { mutableStateOf<CartLine?>(null) }
    var priceFor by remember { mutableStateOf<CartLine?>(null) }
    var pickCustomer by remember { mutableStateOf(false) }
    var billDiscount by remember { mutableStateOf(false) }
    var showHeld by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect {
            when (it) {
                is CheckoutEvent.Completed -> onCompleted(it.saleId)
                is CheckoutEvent.CreateProductForBarcode -> onCreateProduct(it.barcode)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.checkout_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (ui.cart.isNotEmpty()) TextButton(onClick = vm::hold) { Text(stringResource(R.string.hold_bill)) }
                if (held.isNotEmpty()) TextButton(onClick = { showHeld = true }) { Text(stringResource(R.string.held_button, held.size)) }
            },
        )
        OutlinedTextField(
            value = ui.query, onValueChange = vm::setQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_to_add)) },
            trailingIcon = { IconButton(onClick = onScan) { Icon(Icons.Filled.QrCodeScanner, stringResource(R.string.scan_barcode)) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.query.isNotBlank() -> SearchResults(results) { vm.addProduct(it) }
                ui.cart.isEmpty() -> CenterText(R.string.cart_empty, R.string.cart_empty_hint)
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.cart, key = { it.productId }) { line ->
                        CartLineCard(
                            line,
                            onMinus = { vm.step(line.productId, -1) }, onPlus = { vm.step(line.productId, 1) },
                            onQty = { qtyFor = line }, onPrice = { priceFor = line }, onDiscount = { discountFor = line },
                            onRemove = { vm.remove(line.productId) },
                            onUnit = { usePack -> vm.usePackUnit(line.productId, usePack) },
                            onBatch = { vm.openBatchPicker(line.productId) },
                            canPrice = canPrice, canDiscount = canDiscount, canOverrideBatch = canOverrideBatch,
                        )
                    }
                }
            }
        }

        if (ui.cart.isNotEmpty()) SummaryPanel(ui, vm, canDiscount, canCredit, onPickCustomer = { pickCustomer = true }, onBillDiscount = { billDiscount = true })
    }

    qtyFor?.let { l ->
        NumberDialog(stringResource(R.string.edit_quantity), stringResource(R.string.field_qty), Parsing.milliToInput(l.displayQtyMilli), l.unitSymbol,
            onConfirm = { vm.setQty(l.productId, it); qtyFor = null }, onDismiss = { qtyFor = null })
    }
    priceFor?.let { l ->
        NumberDialog(stringResource(R.string.change_price), stringResource(R.string.field_price), Parsing.paiseToInput(l.unitPricePaise), null,
            onConfirm = { vm.setPrice(l.productId, it); priceFor = null }, onDismiss = { priceFor = null })
    }
    discountFor?.let { l ->
        NumberDialog(stringResource(R.string.discount), stringResource(R.string.field_discount),
            if (l.discountPaise > 0) Parsing.paiseToInput(l.discountPaise) else "", null,
            onConfirm = { vm.setDiscount(l.productId, it); discountFor = null }, onDismiss = { discountFor = null })
    }
    if (billDiscount) BillDiscountDialog(onConfirm = { t, pct -> vm.setBillDiscount(t, pct); billDiscount = false }, onDismiss = { billDiscount = false })
    if (pickCustomer) {
        CustomerPicker(customers, canAdd = canManageCustomers, onPick = { vm.setCustomer(it); pickCustomer = false }, onAdd = { n, p -> vm.addCustomer(n, p); pickCustomer = false },
            onDismiss = { pickCustomer = false })
    }
    if (showHeld) HeldDialog(held, onResume = { vm.resume(it); showHeld = false }, onDelete = vm::deleteHeld, onDismiss = { showHeld = false })
    LaunchedEffect(held.isEmpty()) { if (held.isEmpty()) showHeld = false }
    ui.batchPickerProductId?.let { productId ->
        ui.cart.firstOrNull { it.productId == productId }?.let { line ->
            BatchPicker(
                batches = ui.batchOptions[productId].orEmpty(),
                symbol = line.baseUnitSymbol,
                selectedBatchId = line.preferredBatchId,
                onSelect = { vm.selectBatch(productId, it) },
                onDismiss = vm::dismissBatchPicker,
            )
        }
    }

    ui.unknownBarcode?.let { barcode ->
        AlertDialog(
            onDismissRequest = vm::dismissUnknownBarcode,
            title = { Text(stringResource(R.string.barcode_product_not_found_title)) },
            text = { Text(stringResource(R.string.barcode_product_not_found_body, barcode)) },
            confirmButton = {
                TextButton(onClick = vm::createProductForUnknownBarcode) {
                    Text(stringResource(R.string.barcode_create_product))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissUnknownBarcode) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SearchResults(rows: List<ProductListRow>, onAdd: (String) -> Unit) {
    if (rows.isEmpty()) { CenterText(R.string.search_no_results, null); return }
    LazyColumn {
        items(rows, key = { it.id }) { r ->
            ListItem(
                modifier = Modifier.clickable { onAdd(r.id) },
                headlineContent = { Text(r.name) },
                supportingContent = {
                    Text(stringResource(R.string.in_stock_line, Money(r.sellPricePaise).format(), Parsing.milliToInput(r.onHandMilli), r.unitSymbol))
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CenterText(title: Int, hint: Int?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        hint?.let { Spacer(Modifier.height(8.dp)); Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun CartLineCard(
    line: CartLine, onMinus: () -> Unit, onPlus: () -> Unit, onQty: () -> Unit, onPrice: () -> Unit, onDiscount: () -> Unit,
    onRemove: () -> Unit, onUnit: (Boolean) -> Unit, onBatch: () -> Unit,
    canPrice: Boolean, canDiscount: Boolean, canOverrideBatch: Boolean,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(line.name, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.line_price_qty, Money(line.unitPricePaise).format(), Parsing.milliToInput(line.qtyMilli), line.baseUnitSymbol),
                            style = MaterialTheme.typography.bodySmall, modifier = if (canPrice) Modifier.clickable(onClick = onPrice) else Modifier,
                        )
                        if (line.unitPricePaise != line.listPricePaise) {
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.price_was, Money(line.listPricePaise).format()), style = MaterialTheme.typography.bodySmall,
                                textDecoration = TextDecoration.LineThrough)
                        }
                    }
                }
                Text(Money(line.grossPaise - line.discountPaise).format(), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, null) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, null) }
                TextButton(onClick = onQty) { Text("${Parsing.milliToInput(line.displayQtyMilli)} ${line.unitSymbol}", style = MaterialTheme.typography.titleMedium) }
                FilledTonalIconButton(onClick = onPlus) { Icon(Icons.Filled.Add, null) }
                Spacer(Modifier.weight(1f))
                if (canPrice) TextButton(onClick = onPrice) { Text(stringResource(R.string.change_price)) }
                if (canDiscount) TextButton(onClick = onDiscount) {
                    Text(if (line.discountPaise > 0) stringResource(R.string.discount_applied, Money(line.discountPaise).format()) else stringResource(R.string.discount))
                }
            }
            if (canOverrideBatch) TextButton(onClick = onBatch) {
                Text(
                    if (line.preferredBatchLabel == null) stringResource(R.string.sale_batch_auto)
                    else stringResource(R.string.sale_batch_selected, line.preferredBatchLabel),
                )
            }
            if (line.packUnitSymbol != null && line.packSizeMilli != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !line.usingPackUnit,
                        onClick = { onUnit(false) },
                        label = { Text(stringResource(R.string.checkout_add_by_unit, line.baseUnitSymbol)) },
                    )
                    FilterChip(
                        selected = line.usingPackUnit,
                        onClick = { onUnit(true) },
                        label = { Text(stringResource(R.string.checkout_add_by_unit, line.packUnitSymbol)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchPicker(
    batches: List<BatchEntity>,
    symbol: String,
    selectedBatchId: String?,
    onSelect: (BatchEntity?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sale_choose_batch)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { onSelect(null) },
                        headlineContent = { Text(stringResource(R.string.sale_batch_auto)) },
                        supportingContent = { Text(stringResource(R.string.sale_batch_auto_hint)) },
                        trailingContent = { if (selectedBatchId == null) Icon(Icons.Filled.Check, null) },
                    )
                }
                items(batches, key = { it.id }) { batch ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(batch) },
                        headlineContent = { Text(batch.batchNo ?: batch.id.take(8)) },
                        supportingContent = {
                            Text(
                                "${Parsing.milliToInput(batch.qtyOnHandMilli)} $symbol · " +
                                    (batch.expiryDate?.toString() ?: stringResource(R.string.no_expiry)),
                            )
                        },
                        trailingContent = { if (selectedBatchId == batch.id) Icon(Icons.Filled.Check, null) },
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SummaryPanel(ui: CheckoutUi, vm: CheckoutViewModel, canDiscount: Boolean, canCredit: Boolean, onPickCustomer: () -> Unit, onBillDiscount: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = onPickCustomer, label = { Text(ui.customer?.name ?: stringResource(R.string.customer_walkin)) })
                Spacer(Modifier.width(8.dp))
                if (canDiscount) TextButton(onClick = onBillDiscount) {
                    Text(if (ui.billDiscountPaise > 0) stringResource(R.string.bill_discount_applied, Money(ui.billDiscountPaise).format()) else stringResource(R.string.bill_discount))
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.igst_switch), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Switch(checked = ui.interState, onCheckedChange = vm::setInterState)
            }
            if (ui.preview?.soldAgainstNegativeStock == true) {
                Text(stringResource(R.string.negative_stock_warning), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.total), style = MaterialTheme.typography.labelLarge)
                    ui.preview?.let { Text(stringResource(R.string.incl_tax, Money(it.totals.taxPaise).format()), style = MaterialTheme.typography.bodySmall) }
                }
                Text(Money(ui.totalPaise).format(), style = MaterialTheme.typography.headlineMedium)
            }

            // Split payment: cash / UPI / card amounts; whatever is short goes on khata.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PaymentMethod.CASH to R.string.pay_cash, PaymentMethod.UPI to R.string.pay_upi, PaymentMethod.CARD to R.string.pay_card)
                    .forEach { (m, label) ->
                        OutlinedTextField(
                            value = ui.fieldText(m), onValueChange = { vm.setPaid(m, it) }, singleLine = true,
                            label = { Text(stringResource(label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        )
                    }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.payAll(PaymentMethod.CASH) }, label = { Text(stringResource(R.string.pay_all_cash)) })
                AssistChip(onClick = { vm.payAll(PaymentMethod.UPI) }, label = { Text(stringResource(R.string.pay_all_upi)) })
                AssistChip(onClick = { vm.payAll(PaymentMethod.CARD) }, label = { Text(stringResource(R.string.pay_all_card)) })
                if (canCredit) AssistChip(onClick = { vm.payAll(null) }, label = { Text(stringResource(R.string.pay_later)) })
            }
            if (ui.khataPaise > 0) Text(stringResource(R.string.on_khata, Money(ui.khataPaise).format()), style = MaterialTheme.typography.titleSmall)

            ui.message?.let { m -> Text(msg(m), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = vm::complete, enabled = ui.preview != null && !ui.completing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.complete_sale, Money(ui.totalPaise).format())) }
        }
    }
}

@Composable
private fun msg(e: FieldError): String = if (e.arg != null) stringResource(e.res, e.arg) else stringResource(e.res)

@Composable
private fun NumberDialog(title: String, label: String, initial: String, suffix: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(label) }, suffix = suffix?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BillDiscountDialog(onConfirm: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.bill_discount)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!percent, { percent = false }, { Text(stringResource(R.string.chip_rupees)) })
                    FilterChip(percent, { percent = true }, { Text(stringResource(R.string.chip_percent)) })
                }
                OutlinedTextField(text, { text = it }, singleLine = true,
                    label = { Text(stringResource(if (percent) R.string.field_bill_discount_percent else R.string.field_bill_discount_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text, percent) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun HeldDialog(held: List<HeldCartEntity>, onResume: (String) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.held_title)) },
        text = {
            if (held.isEmpty()) Text(stringResource(R.string.held_empty))
            else LazyColumn {
                items(held, key = { it.id }) { h ->
                    ListItem(
                        headlineContent = { Text(h.label) },
                        supportingContent = { Text(stringResource(R.string.held_items, h.itemCount)) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { onResume(h.id) }) { Text(stringResource(R.string.resume)) }
                                IconButton(onClick = { onDelete(h.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.delete)) }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun CustomerPicker(
    customers: List<CustomerEntity>, canAdd: Boolean, onPick: (CustomerEntity?) -> Unit, onAdd: (String, String) -> Unit, onDismiss: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (adding) R.string.add_customer else R.string.choose_customer)) },
        text = {
            if (adding) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.field_customer_name)) })
                    OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text(stringResource(R.string.field_phone)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
            } else {
                LazyColumn {
                    item { ListItem(modifier = Modifier.clickable { onPick(null) }, headlineContent = { Text(stringResource(R.string.customer_walkin)) }) }
                    items(customers, key = { it.id }) { c ->
                        ListItem(modifier = Modifier.clickable { onPick(c) }, headlineContent = { Text(c.name) }, supportingContent = c.phone?.let { { Text(it) } })
                    }
                }
            }
        },
        confirmButton = {
            if (adding && canAdd) {
                TextButton(onClick = { onAdd(name, phone) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
            } else if (!adding && canAdd) {
                TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add_customer)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
