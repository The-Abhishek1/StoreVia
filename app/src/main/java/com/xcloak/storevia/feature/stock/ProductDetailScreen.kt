package com.xcloak.storevia.feature.stock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
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
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.ui.LoadingState
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.StockMovementEntity
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onAddVariant: (String) -> Unit,
    onOpenVariant: (String) -> Unit,
    vm: ProductDetailViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showReceive by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf<BatchEntity?>(null) }
    var showRecipeEditor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canAdjust = can(Permission.ADJUST_STOCK)
    val canEdit = can(Permission.MANAGE_PRODUCTS)
    val canReceive = can(Permission.MANAGE_PURCHASES)
    val d = ui

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    d?.let { listOfNotNull(it.product.name, it.product.variantLabel).joinToString(" · ") }.orEmpty(),
                    maxLines = 1,
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (d != null && canEdit) IconButton(onClick = { onEdit(d.product.id) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
            },
        )
        if (d == null) {
            LoadingState(Modifier.weight(1f).fillMaxWidth())
            return@Column
        }

        val symbol = d.unit?.symbol.orEmpty()
        val attributes = remember(d.product.attributesJson) {
            runCatching {
                Json.parseToJsonElement(d.product.attributesJson).jsonObject.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { key to it }
                }
            }.getOrDefault(emptyList())
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.stock_on_hand), style = MaterialTheme.typography.labelLarge)
                Text("${Parsing.milliToInput(d.onHandMilli)} $symbol", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(Money(d.product.sellPricePaise).format())
                        append("  ·  "); append("GST ${d.product.gstRateBp / 100}%")
                        d.barcode?.let { append("  ·  "); append(it) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.batches), style = MaterialTheme.typography.titleMedium)
            }
            if (attributes.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.product_type_details), style = MaterialTheme.typography.titleMedium)
                            attributes.forEach { (key, value) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(key.replace('_', ' '), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            if (d.product.parentProductId == null) {
                item {
                    Text(stringResource(R.string.product_variants_title), style = MaterialTheme.typography.titleMedium)
                    if (d.variants.isEmpty()) {
                        Text(stringResource(R.string.product_variants_empty), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        d.variants.forEach { variant ->
                            ListItem(
                                modifier = Modifier.clickable { onOpenVariant(variant.id) },
                                headlineContent = { Text(variant.variantLabel) },
                                supportingContent = {
                                    Text(
                                        listOfNotNull(
                                            variant.sku,
                                            "${Parsing.milliToInput(variant.onHandMilli)} ${variant.unitSymbol.orEmpty()}",
                                            Money(variant.sellPricePaise).format(),
                                        )
                                            .joinToString(" · "),
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (canEdit && !d.hasStockHistory) {
                        TextButton(onClick = { onAddVariant(d.product.id) }) {
                            Text(stringResource(R.string.product_add_variant))
                        }
                    }
                    if (d.variants.isNotEmpty()) {
                        Text(
                            stringResource(R.string.product_variant_stock_separate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (canEdit || d.recipeComponents.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.recipe_bom_title), style = MaterialTheme.typography.titleMedium)
                                if (canEdit) TextButton(onClick = { showRecipeEditor = true }) {
                                    Text(stringResource(R.string.recipe_add_ingredient))
                                }
                            }
                            if (d.recipeComponents.isEmpty()) {
                                Text(stringResource(R.string.recipe_bom_empty), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(stringResource(R.string.recipe_bom_hint), style = MaterialTheme.typography.bodySmall)
                                d.recipeComponents.forEach { component ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${component.ingredientName} · ${Parsing.milliToInput(component.quantityPerUnitMilli)} ${component.unitSymbol}",
                                            Modifier.weight(1f),
                                        )
                                        if (canEdit) TextButton(onClick = {
                                            scope.launch { vm.removeRecipeIngredient(component.id) }
                                        }) { Text(stringResource(R.string.delete)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (d.product.serialTracked) {
                item {
                    Text(stringResource(R.string.serial_inventory_title), style = MaterialTheme.typography.titleMedium)
                    if (d.serialNumbers.isEmpty()) {
                        Text(stringResource(R.string.serial_inventory_empty), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        d.serialNumbers.forEach { serial ->
                            ListItem(
                                headlineContent = { Text(serial.serialNumber) },
                                supportingContent = { Text(stringResource(R.string.serial_inventory_status, serial.status.name.lowercase())) },
                            )
                        }
                    }
                }
            }
            if (d.batches.isEmpty()) {
                item { Text(stringResource(R.string.no_batches), style = MaterialTheme.typography.bodyMedium) }
            } else {
                if (canAdjust) item { Text(stringResource(R.string.tap_batch_hint), style = MaterialTheme.typography.bodySmall) }
                items(d.batches, key = { it.id }) { BatchRow(it, symbol) { if (canAdjust) adjusting = it } }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.activity), style = MaterialTheme.typography.titleMedium)
            }
            if (d.movements.isEmpty()) {
                item { Text(stringResource(R.string.activity_empty), style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(d.movements, key = { it.id }) { MovementRow(it, symbol) }
            }
        }
        if (canReceive && d.variants.isEmpty() && d.recipeComponents.isEmpty()) Button(onClick = { showReceive = true }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
            Text(stringResource(R.string.receive_stock))
        }
    }

    adjusting?.let { b -> AdjustDialog(vm, b, d?.unit?.symbol.orEmpty()) { adjusting = null } }
    if (showReceive && d != null) ReceiveDialog(
        vm,
        d.product.trackExpiry,
        d.product.serialTracked,
        d.unit?.symbol.orEmpty(),
        d.product.costPaise,
    ) { showReceive = false }
    if (showRecipeEditor && d != null) RecipeComponentDialog(vm, d.product.id, d.recipeComponents, { showRecipeEditor = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeComponentDialog(
    vm: ProductDetailViewModel,
    recipeProductId: String,
    components: List<com.xcloak.storevia.core.database.dao.RecipeComponentRow>,
    onDismiss: () -> Unit,
) {
    val products by vm.ingredientOptions.collectAsStateWithLifecycle()
    var ingredientId by remember { mutableStateOf(products.firstOrNull { it.id != recipeProductId }?.id.orEmpty()) }
    var quantity by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val ingredient = products.firstOrNull { it.id == ingredientId }
    val unitSymbol = vm.unitSymbol(ingredientId).orEmpty()
    val available = products.filter { product ->
        product.id != recipeProductId && components.none { it.ingredientProductId == product.id }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recipe_add_ingredient)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = products.firstOrNull { it.id == ingredientId }?.name.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.recipe_ingredient)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        available.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                onClick = { ingredientId = option.id; expanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it; error = null },
                    label = { Text(stringResource(R.string.recipe_quantity_per_unit)) },
                    supportingText = { Text(stringResource(R.string.recipe_quantity_hint, unitSymbol)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ingredientId.isNotBlank() && quantity.isNotBlank(),
                onClick = {
                    scope.launch {
                        val result = vm.saveRecipeIngredient(ingredientId, quantity)
                        if (result == null) onDismiss() else error = result
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BatchRow(b: BatchEntity, symbol: String, onClick: () -> Unit) {
    val today = LocalDate.now()
    val exp = b.expiryDate
    val days = exp?.let { ChronoUnit.DAYS.between(today, it) }
    val color = when {
        days == null -> MaterialTheme.colorScheme.onSurfaceVariant
        days < 0 -> MaterialTheme.colorScheme.error
        days <= 30 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text("${Parsing.milliToInput(b.qtyOnHandMilli)} $symbol") },
        supportingContent = {
            Text(
                when {
                    exp == null -> stringResource(R.string.no_expiry)
                    days!! < 0 -> stringResource(R.string.expired) + " · " + exp.format(DATE_FMT)
                    else -> stringResource(R.string.expires_on, exp.format(DATE_FMT))
                },
                color = color,
            )
        },
        trailingContent = { b.batchNo?.let { Text(stringResource(R.string.batch_label, it)) } },
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveDialog(
    vm: ProductDetailViewModel,
    trackExpiry: Boolean,
    serialTracked: Boolean,
    symbol: String,
    defaultCost: Long,
    onDismiss: () -> Unit,
) {
    var qty by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf(Parsing.paiseToInput(defaultCost)) }
    var batchNo by remember { mutableStateOf("") }
    var serialNumbers by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf<LocalDate?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.receive_stock)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(qty, { qty = it; error = null }, singleLine = true, suffix = { Text(symbol) },
                    label = { Text(stringResource(R.string.field_qty)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(cost, { cost = it; error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.field_cost)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(batchNo, { batchNo = it }, singleLine = true, label = { Text(stringResource(R.string.field_batch_no)) })
                if (serialTracked) {
                    OutlinedTextField(
                        value = serialNumbers,
                        onValueChange = { serialNumbers = it; error = null },
                        minLines = 2,
                        label = { Text(stringResource(R.string.product_serial_numbers)) },
                        supportingText = { Text(stringResource(R.string.serial_numbers_entry_hint)) },
                    )
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(expiry?.format(DATE_FMT) ?: stringResource(if (trackExpiry) R.string.field_expiry else R.string.pick_date))
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val e = vm.receive(qty, cost, batchNo, expiry, serialNumbers)
                    if (e == null) onDismiss() else error = e
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (picking) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { expiry = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

private val ACTIVITY_DATE = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
private fun MovementRow(m: StockMovementEntity, symbol: String) {
    val label = when (m.type) {
        MovementType.PURCHASE -> R.string.move_purchase
        MovementType.SALE -> R.string.move_sale
        MovementType.SALE_RETURN -> R.string.move_sale_return
        MovementType.PURCHASE_RETURN -> R.string.move_purchase_return
        MovementType.DAMAGE -> R.string.move_damage
        MovementType.EXPIRED -> R.string.move_expired
        MovementType.ADJUSTMENT_IN -> R.string.move_adj_in
        MovementType.ADJUSTMENT_OUT -> R.string.move_adj_out
        MovementType.OPENING -> R.string.move_opening
    }
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = {
            Text(
                java.time.Instant.ofEpochMilli(m.createdAt).atZone(java.time.ZoneId.systemDefault()).format(ACTIVITY_DATE) +
                    (m.reason?.let { " · $it" } ?: "")
            )
        },
        trailingContent = {
            val sign = if (m.qtyMilli > 0) "+" else "−"
            Text(sign + Parsing.milliToInput(kotlin.math.abs(m.qtyMilli)) + " " + symbol, style = MaterialTheme.typography.titleSmall)
        },
    )
    HorizontalDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdjustDialog(vm: ProductDetailViewModel, batch: BatchEntity, symbol: String, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(MovementType.DAMAGE) }
    var qty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val reasons = listOf(
        MovementType.DAMAGE to R.string.reason_damaged, MovementType.EXPIRED to R.string.reason_expired,
        MovementType.ADJUSTMENT_OUT to R.string.reason_count_remove, MovementType.ADJUSTMENT_IN to R.string.reason_count_add,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adjust_stock)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Parsing.milliToInput(batch.qtyOnHandMilli)} $symbol" + (batch.batchNo?.let { " · " + stringResource(R.string.batch_label, it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    reasons.forEach { (t, label) -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(stringResource(label)) }) }
                }
                OutlinedTextField(qty, { qty = it; error = null }, singleLine = true, suffix = { Text(symbol) },
                    label = { Text(stringResource(R.string.field_qty)) }, isError = error != null,
                    supportingText = error?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(note, { note = it }, singleLine = true, label = { Text(stringResource(R.string.field_note)) })
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { val e = vm.adjust(batch.id, type, qty, note); if (e == null) onDismiss() else error = e } }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
