package com.xcloak.storevia.feature.suppliers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.core.ui.LoadingState
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.core.ui.can
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun Long.asSupplierDate() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(DATE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierDetailScreen(
    onBack: () -> Unit,
    onNewPurchase: (String) -> Unit,
    onOpenPurchase: (String) -> Unit,
    vm: SupplierDetailViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var paying by remember { mutableStateOf(false) }
    var oldBalance by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val d = state.detail

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(d?.supplier?.name.orEmpty(), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (d != null && can(Permission.MANAGE_PURCHASES)) {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                }
            },
        )
        if (!state.loaded) {
            LoadingState(Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        if (d == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.supplier_unavailable), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
            return@Column
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.balance_label), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Money(kotlin.math.abs(d.balancePaise)).format(), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                PayableText(d.balancePaise)
            }
            d.supplier.phone?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            d.supplier.gstin?.let { Text(stringResource(R.string.gstin_label, it), style = MaterialTheme.typography.bodySmall) }

            if (d.cloudLimited) {
                Text(
                    "Cloud purchase entry and supplier payments are unavailable until their stock-safe server transactions are implemented.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (can(Permission.MANAGE_PURCHASES) && !d.cloudLimited) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onNewPurchase(d.supplier.id) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.new_purchase)) }
                    OutlinedButton(
                        onClick = { paying = true },
                        enabled = d.balancePaise > 0 && (d.unpaidPurchases.isNotEmpty() || d.unallocatedBalancePaise > 0),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.pay_supplier)) }
                }
            }
            if (can(Permission.MANAGE_PURCHASES)) {
                TextButton(onClick = { oldBalance = true }) { Text(stringResource(R.string.add_old_payable)) }
            }
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
                        modifier = if (!d.cloudLimited && e.refType == "PURCHASE" && e.refId != null) Modifier.clickable { onOpenPurchase(e.refId) } else Modifier,
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

    if (paying && d != null) PayDialog(d, vm) { paying = false }
    if (oldBalance) OldPayableDialog(vm) { oldBalance = false }
    if (editing && d != null && can(Permission.MANAGE_PURCHASES)) {
        SupplierFormDialog(d.supplier, onSave = { n, p, g -> vm.update(n, p, g) }, onDismiss = { editing = false })
    }
}

private const val UNALLOCATED_PAYMENT_TARGET = "supplier-balance"

@Composable
private fun PayDialog(detail: SupplierDetailUi, vm: SupplierDetailViewModel, onDismiss: () -> Unit) {
    val firstPurchase = detail.unpaidPurchases.firstOrNull()
    var selectedTarget by rememberSaveable { mutableStateOf(firstPurchase?.id ?: UNALLOCATED_PAYMENT_TARGET) }
    val selectedPurchase = detail.unpaidPurchases.firstOrNull { it.id == selectedTarget }
    val availablePaise = if (selectedTarget == UNALLOCATED_PAYMENT_TARGET) {
        minOf(detail.balancePaise, detail.unallocatedBalancePaise).coerceAtLeast(0)
    } else {
        minOf(
            detail.balancePaise,
            (selectedPurchase?.totalPaise ?: 0L) - (selectedPurchase?.paidPaise ?: 0L),
        ).coerceAtLeast(0)
    }
    var amount by remember { mutableStateOf(Parsing.paiseToInput(availablePaise)) }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.pay_supplier)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.supplier_payment_allocate_hint), style = MaterialTheme.typography.bodySmall)
                detail.unpaidPurchases.forEach { purchase ->
                    val duePaise = (purchase.totalPaise - purchase.paidPaise).coerceAtLeast(0)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedTarget = purchase.id
                            amount = Parsing.paiseToInput(minOf(detail.balancePaise, duePaise).coerceAtLeast(0))
                            error = null
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedTarget == purchase.id, onClick = {
                            selectedTarget = purchase.id
                            amount = Parsing.paiseToInput(minOf(detail.balancePaise, duePaise).coerceAtLeast(0))
                            error = null
                        })
                        Column(Modifier.weight(1f)) {
                            Text(purchase.invoiceNo ?: stringResource(R.string.purchase_title))
                            Text(
                                listOfNotNull(purchase.purchaseDate.asSupplierDate(), purchase.branchName).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(
                                    R.string.supplier_payment_purchase_due,
                                    Money(duePaise).format(),
                                    Money(minOf(detail.balancePaise, duePaise).coerceAtLeast(0)).format(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (detail.unallocatedBalancePaise > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedTarget = UNALLOCATED_PAYMENT_TARGET
                            amount = Parsing.paiseToInput(minOf(detail.balancePaise, detail.unallocatedBalancePaise).coerceAtLeast(0))
                            error = null
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedTarget == UNALLOCATED_PAYMENT_TARGET, onClick = {
                            selectedTarget = UNALLOCATED_PAYMENT_TARGET
                            amount = Parsing.paiseToInput(minOf(detail.balancePaise, detail.unallocatedBalancePaise).coerceAtLeast(0))
                            error = null
                        })
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.supplier_unallocated_balance))
                            Text(
                                stringResource(
                                    R.string.supplier_payment_available,
                                    Money(minOf(detail.balancePaise, detail.unallocatedBalancePaise)).format(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                OutlinedTextField(amount, { amount = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.field_amount)) },
                    isError = error != null, supportingText = error?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PaymentMethod.CASH to R.string.pay_cash, PaymentMethod.UPI to R.string.pay_upi, PaymentMethod.CARD to R.string.pay_card)
                        .forEach { (m, l) -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(stringResource(l)) }) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && availablePaise > 0 &&
                    (selectedTarget == UNALLOCATED_PAYMENT_TARGET && detail.unallocatedBalancePaise > 0 || selectedPurchase != null),
                onClick = {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                val purchaseId = selectedTarget.takeUnless { it == UNALLOCATED_PAYMENT_TARGET }
                                val e = vm.pay(amount, method, purchaseId)
                                if (e == null) onDismiss() else error = e
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OldPayableDialog(vm: SupplierDetailViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.add_old_payable)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.old_payable_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(amount, { amount = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.field_amount)) },
                    isError = error != null, supportingText = error?.let { { Text(stringResource(it)) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try {
                                val e = vm.addOldBalance(amount)
                                if (e == null) onDismiss() else error = e
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}
