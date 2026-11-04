package com.xcloak.storevia.feature.purchases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PurchaseEditScreen(onBack: () -> Unit, onScan: () -> Unit, onSaved: (String) -> Unit, vm: PurchaseEditViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val suppliers by vm.suppliers.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    var pickSupplier by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.events.collect { if (it is PurchaseEvent.Saved) onSaved(it.purchaseId) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.new_purchase)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(onClick = { pickSupplier = true }, label = { Text(ui.supplier?.name ?: stringResource(R.string.no_supplier)) })
            OutlinedTextField(ui.invoiceNo, vm::setInvoice, singleLine = true, label = { Text(stringResource(R.string.field_supplier_invoice)) }, modifier = Modifier.weight(1f))
            AssistChip(onClick = { pickDate = true }, label = { Text(ui.date.format(DATE)) })
        }
        OutlinedTextField(
            value = ui.query, onValueChange = vm::setQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_to_add)) },
            trailingIcon = { IconButton(onClick = onScan) { Icon(Icons.Filled.QrCodeScanner, stringResource(R.string.scan_barcode)) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.query.isNotBlank() -> LazyColumn {
                    if (results.isEmpty()) item { Text(stringResource(R.string.search_no_results), Modifier.padding(24.dp)) }
                    items(results, key = { it.id }) { r ->
                        ListItem(
                            modifier = Modifier.clickable { vm.addProduct(r.id) },
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text(stringResource(R.string.in_stock_line, Money(r.sellPricePaise).format(), Parsing.milliToInput(r.onHandMilli), r.unitSymbol)) },
                        )
                        HorizontalDivider()
                    }
                }
                ui.lines.isEmpty() -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.purchase_empty), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.purchase_empty_hint), style = MaterialTheme.typography.bodyMedium)
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.lines, key = { it.lineId }) { l ->
                        OutlinedCard(Modifier.fillMaxWidth().clickable { vm.openLine(l.lineId) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (l.usingPackUnit) stringResource(
                                            R.string.purchase_line_pack_qty,
                                            Parsing.milliToInput(l.displayQtyMilli),
                                            l.unitSymbol,
                                            l.baseUnitSymbol,
                                        ) else stringResource(
                                            R.string.line_price_qty,
                                            Money(l.unitCostPaise).format(),
                                            Parsing.milliToInput(l.qtyMilli),
                                            l.unitSymbol,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    val extra = listOfNotNull(l.batchNo?.let { stringResource(R.string.batch_label, it) }, l.expiry?.let { stringResource(R.string.expires_on, it.format(DATE)) })
                                    if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(Money(l.totalPaise).format(), style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { vm.removeLine(l.lineId) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, null) }
                            }
                        }
                    }
                }
            }
        }

        if (ui.lines.isNotEmpty()) Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        ui.billTotalText, vm::setBillTotal, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.field_bill_total)) },
                        placeholder = { Text(Parsing.paiseToInput(ui.linesTotalPaise)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        ui.paidText, vm::setPaid, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.field_paid_now)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = vm::payInFull, label = { Text(stringResource(R.string.paid_in_full)) })
                    listOf(PaymentMethod.CASH to R.string.pay_cash, PaymentMethod.UPI to R.string.pay_upi, PaymentMethod.CARD to R.string.pay_card)
                        .forEach { (m, l) -> FilterChip(selected = ui.method == m, onClick = { vm.setMethod(m) }, label = { Text(stringResource(l)) }) }
                }
                Text(stringResource(R.string.will_owe, Money(ui.owedPaise).format()), style = MaterialTheme.typography.titleSmall)
                ui.message?.let { m -> Text(if (m.arg != null) stringResource(m.res, m.arg) else stringResource(m.res), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = vm::save, enabled = !ui.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.save_purchase, Money(ui.totalPaise ?: ui.linesTotalPaise).format()))
                }
            }
        }
    }

    ui.editingLineId?.let { id ->
        ui.lines.firstOrNull { it.lineId == id }?.let { line ->
            LineDialog(
                line,
                supportsBatchTracking = features.supportsBatchTracking,
                supportsExpiryTracking = features.supportsExpiryTracking,
                onSave = { q, c, b, e, usePack, serials -> vm.updateLine(id, q, c, b, e, usePack, serials) },
                onDismiss = { vm.openLine(null) },
            )
        }
    }
    if (pickSupplier) SupplierPicker(suppliers, onPick = { vm.setSupplier(it); pickSupplier = false }, onAdd = { vm.addSupplier(it); pickSupplier = false }, onDismiss = { pickSupplier = false })
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = ui.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; pickDate = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LineDialog(
    line: PurchaseLineUi,
    supportsBatchTracking: Boolean,
    supportsExpiryTracking: Boolean,
    onSave: (String, String, String, LocalDate?, Boolean, String) -> Int?,
    onDismiss: () -> Unit,
) {
    var usingPackUnit by remember { mutableStateOf(line.usingPackUnit) }
    var qty by remember { mutableStateOf(Parsing.milliToInput(line.displayQtyMilli)) }
    var cost by remember { mutableStateOf(Parsing.paiseToInput(line.unitCostPaise)) }
    var batch by remember { mutableStateOf(line.batchNo.orEmpty()) }
    var expiry by remember { mutableStateOf(line.expiry) }
    var serialNumbers by remember { mutableStateOf(line.serialNumbersText) }
    var error by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (line.packUnitSymbol != null && line.packSizeMilli != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !usingPackUnit,
                            onClick = {
                                if (usingPackUnit) {
                                    qty = ""
                                    usingPackUnit = false
                                }
                            },
                            label = { Text(line.baseUnitSymbol) },
                        )
                        FilterChip(
                            selected = usingPackUnit,
                            onClick = {
                                if (!usingPackUnit) {
                                    qty = ""
                                    usingPackUnit = true
                                }
                            },
                            label = { Text(line.packUnitSymbol) },
                        )
                    }
                }
                OutlinedTextField(
                    qty,
                    { qty = it; error = null },
                    singleLine = true,
                    suffix = { Text(if (usingPackUnit) line.packUnitSymbol.orEmpty() else line.baseUnitSymbol) },
                    label = { Text(stringResource(R.string.field_qty)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(
                    cost,
                    { cost = it; error = null },
                    singleLine = true,
                    label = { Text(stringResource(R.string.field_unit_cost_base, line.baseUnitSymbol)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (line.serialTracked) {
                    OutlinedTextField(
                        value = serialNumbers,
                        onValueChange = { serialNumbers = it; error = null },
                        minLines = 2,
                        label = { Text(stringResource(R.string.product_serial_numbers)) },
                        supportingText = { Text(stringResource(R.string.serial_numbers_entry_hint)) },
                    )
                }
                if (line.packUnitSymbol != null && line.packSizeMilli != null) Text(
                    stringResource(R.string.purchase_pack_conversion_note, line.baseUnitSymbol),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (supportsBatchTracking) {
                    OutlinedTextField(batch, { batch = it }, singleLine = true, label = { Text(stringResource(R.string.field_batch_no)) })
                }
                if (supportsExpiryTracking) {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(expiry?.format(DATE) ?: stringResource(if (line.trackExpiry) R.string.field_expiry else R.string.pick_date))
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { val e = onSave(qty, cost, batch, expiry, usingPackUnit, serialNumbers); if (e == null) onDismiss() else error = e }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (picking && supportsExpiryTracking) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { expiry = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; picking = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun SupplierPicker(suppliers: List<SupplierEntity>, onPick: (SupplierEntity?) -> Unit, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (adding) R.string.add_supplier else R.string.choose_supplier)) },
        text = {
            if (adding) OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.field_supplier_name)) })
            else LazyColumn {
                item { ListItem(modifier = Modifier.clickable { onPick(null) }, headlineContent = { Text(stringResource(R.string.no_supplier)) }) }
                items(suppliers, key = { it.id }) { s -> ListItem(modifier = Modifier.clickable { onPick(s) }, headlineContent = { Text(s.name) }, supportingContent = s.phone?.let { { Text(it) } }) }
            }
        },
        confirmButton = {
            if (adding) TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.save)) }
            else TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add_supplier)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
