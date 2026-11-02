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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.StockMovementEntity
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(onBack: () -> Unit, onEdit: (String) -> Unit, vm: ProductDetailViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showReceive by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf<BatchEntity?>(null) }
    val canAdjust = can(Permission.ADJUST_STOCK)
    val canEdit = can(Permission.MANAGE_PRODUCTS)
    val canReceive = can(Permission.MANAGE_PURCHASES)
    val d = ui

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(d?.product?.name.orEmpty(), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (d != null && canEdit) IconButton(onClick = { onEdit(d.product.id) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
            },
        )
        if (d == null) return@Column

        val symbol = d.unit?.symbol.orEmpty()
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
        if (canReceive) Button(onClick = { showReceive = true }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
            Text(stringResource(R.string.receive_stock))
        }
    }

    adjusting?.let { b -> AdjustDialog(vm, b, d?.unit?.symbol.orEmpty()) { adjusting = null } }
    if (showReceive && d != null) ReceiveDialog(vm, d.product.trackExpiry, d.unit?.symbol.orEmpty(), d.product.costPaise) { showReceive = false }
}

@Composable
private fun BatchRow(b: BatchEntity, symbol: String, onClick: () -> Unit) {
    val today = LocalDate.now()
    val exp = b.expiryDate
    val days = exp?.let { ChronoUnit.DAYS.between(today, it) }
    val color = when {
        days == null -> MaterialTheme.colorScheme.onSurfaceVariant
        days < 0 -> MaterialTheme.colorScheme.error
        days <= 30 -> Color(0xFFB26A00)
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
private fun ReceiveDialog(vm: ProductDetailViewModel, trackExpiry: Boolean, symbol: String, defaultCost: Long, onDismiss: () -> Unit) {
    var qty by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf(Parsing.paiseToInput(defaultCost)) }
    var batchNo by remember { mutableStateOf("") }
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
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(expiry?.format(DATE_FMT) ?: stringResource(if (trackExpiry) R.string.field_expiry else R.string.pick_date))
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val e = vm.receive(qty, cost, batchNo, expiry)
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
