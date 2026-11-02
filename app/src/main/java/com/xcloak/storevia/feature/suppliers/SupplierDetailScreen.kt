package com.xcloak.storevia.feature.suppliers

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
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierDetailScreen(
    onBack: () -> Unit,
    onNewPurchase: (String) -> Unit,
    onOpenPurchase: (String) -> Unit,
    vm: SupplierDetailViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var paying by remember { mutableStateOf(false) }
    var oldBalance by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val d = ui

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(d?.supplier?.name.orEmpty(), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { if (d != null) IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) } },
        )
        if (d == null) return@Column

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.balance_label), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Money(kotlin.math.abs(d.balancePaise)).format(), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                PayableText(d.balancePaise)
            }
            d.supplier.phone?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            d.supplier.gstin?.let { Text(stringResource(R.string.gstin_label, it), style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onNewPurchase(d.supplier.id) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.new_purchase)) }
                OutlinedButton(onClick = { paying = true }, enabled = d.balancePaise > 0, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pay_supplier)) }
            }
            TextButton(onClick = { oldBalance = true }) { Text(stringResource(R.string.add_old_payable)) }
            Text(stringResource(R.string.ledger_title), style = MaterialTheme.typography.titleMedium)
        }

        if (d.ledger.isEmpty()) {
            Text(stringResource(R.string.ledger_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(d.ledger, key = { it.entry.id }) { row ->
                    val e = row.entry
                    val label = when (e.type) {
                        SupplierLedgerType.PURCHASE -> R.string.sledger_purchase
                        SupplierLedgerType.PAYMENT -> R.string.sledger_payment
                        SupplierLedgerType.ADJUSTMENT -> R.string.sledger_adjustment
                    }
                    ListItem(
                        modifier = if (e.refType == "PURCHASE" && e.refId != null) Modifier.clickable { onOpenPurchase(e.refId) } else Modifier,
                        headlineContent = { Text(stringResource(label)) },
                        supportingContent = {
                            Text(Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).format(DATE) + " · " +
                                stringResource(R.string.ledger_running, Money(row.runningPaise).format()))
                        },
                        trailingContent = {
                            val sign = if (e.amountPaise > 0) "+" else "−"
                            Text(sign + Money(kotlin.math.abs(e.amountPaise)).format(),
                                color = if (e.amountPaise > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleSmall)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (paying && d != null) PayDialog(d.balancePaise, vm) { paying = false }
    if (oldBalance) OldPayableDialog(vm) { oldBalance = false }
    if (editing && d != null) SupplierFormDialog(d.supplier, onSave = { n, p, g -> vm.update(n, p, g) }, onDismiss = { editing = false })
}

@Composable
private fun PayDialog(balancePaise: Long, vm: SupplierDetailViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf(Parsing.paiseToInput(balancePaise)) }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pay_supplier)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.field_amount)) },
                    isError = error != null, supportingText = error?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PaymentMethod.CASH to R.string.pay_cash, PaymentMethod.UPI to R.string.pay_upi, PaymentMethod.CARD to R.string.pay_card)
                        .forEach { (m, l) -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(stringResource(l)) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { val e = vm.pay(amount, method); if (e == null) onDismiss() else error = e } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OldPayableDialog(vm: SupplierDetailViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_old_payable)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.old_payable_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(amount, { amount = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.field_amount)) },
                    isError = error != null, supportingText = error?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { val e = vm.addOldBalance(amount); if (e == null) onDismiss() else error = e } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
