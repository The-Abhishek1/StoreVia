package com.xcloak.storevia.feature.sales

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.database.entity.SaleStatus
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.ui.LoadingState
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.util.Parsing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RECEIPT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleDetailScreen(onBack: () -> Unit, onReturn: (String) -> Unit, vm: SaleDetailViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.receipt)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
            actions = {
                ui?.let { receipt ->
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, receipt.shareText)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }) {
                        Icon(Icons.Filled.Share, stringResource(R.string.share_receipt))
                    }
                }
            },
        )
        val receipt = ui
        if (receipt == null) {
            LoadingState(Modifier.weight(1f).fillMaxWidth())
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(receipt.store.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    listOf(receipt.store.address, receipt.store.city).filter(String::isNotBlank).joinToString(", ")
                        .takeIf(String::isNotBlank)
                        ?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                    if (receipt.store.phone.isNotBlank()) {
                        Text(receipt.store.phone, style = MaterialTheme.typography.bodyMedium)
                    }
                    receipt.store.gstin?.let {
                        Text(stringResource(R.string.gstin_label, it), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    ReceiptStatus(receipt)
                    Text(
                        stringResource(R.string.invoice_label, receipt.sale.invoiceNo),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        Instant.ofEpochMilli(receipt.sale.createdAt).atZone(ZoneId.systemDefault()).format(RECEIPT_DATE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    receipt.customerName?.let {
                        Text(stringResource(R.string.receipt_customer, it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.receipt_items), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.receipt_item), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.receipt_quantity), modifier = Modifier.widthIn(min = 60.dp), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.receipt_amount), modifier = Modifier.widthIn(min = 88.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                    receipt.items.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.receipt_unit_price, Money(item.unitPricePaise).format()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                Parsing.milliToInput(item.qtyMilli),
                                modifier = Modifier.widthIn(min = 60.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                Money(item.amountPaise).format(),
                                modifier = Modifier.widthIn(min = 88.dp),
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (receipt.serialNumbers.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.receipt_serial_numbers, receipt.serialNumbers.joinToString { it.serialNumber }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val cgst = receipt.lines.sumOf { it.cgstPaise }
            val sgst = receipt.lines.sumOf { it.sgstPaise }
            val igst = receipt.lines.sumOf { it.igstPaise }
            val due = (receipt.sale.totalPaise - receipt.sale.returnedPaise - receipt.sale.paidPaise).coerceAtLeast(0L)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.receipt_payment_summary), style = MaterialTheme.typography.titleMedium)
                    AmountRow(stringResource(R.string.taxable_value), receipt.sale.subtotalPaise)
                    if (igst > 0) AmountRow(stringResource(R.string.receipt_igst), igst)
                    else if (cgst + sgst > 0) {
                        AmountRow(stringResource(R.string.receipt_cgst), cgst)
                        AmountRow(stringResource(R.string.receipt_sgst), sgst)
                    }
                    if (receipt.sale.discountPaise > 0) AmountRow(stringResource(R.string.discount), -receipt.sale.discountPaise)
                    if (receipt.sale.roundOffPaise != 0L) AmountRow(stringResource(R.string.round_off), receipt.sale.roundOffPaise)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.total), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(Money(receipt.sale.totalPaise).format(), style = MaterialTheme.typography.headlineSmall)
                    }
                    if (receipt.sale.returnedPaise > 0) AmountRow(stringResource(R.string.returned_label), -receipt.sale.returnedPaise)
                    receipt.payments.forEach { payment ->
                        AmountRow(
                            stringResource(
                                R.string.receipt_paid_via,
                                payment.method.name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) },
                            ),
                            payment.amountPaise,
                        )
                    }
                    if (due > 0) {
                        HorizontalDivider()
                        AmountRow(stringResource(R.string.balance_due), due, emphasize = true)
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (receipt.canReturn && can(Permission.PROCESS_RETURN)) {
                OutlinedButton(onClick = { onReturn(receipt.sale.id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.return_items))
                }
            }
            if (receipt.canCancel && can(Permission.CANCEL_BILL)) {
                TextButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cancel_bill), color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.done))
            }
        }
    }

    if (confirmCancel) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.cancel_bill_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cancel_bill_body))
                    OutlinedTextField(
                        reason,
                        { reason = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.field_cancel_reason)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; vm.cancel(reason) }) {
                    Text(stringResource(R.string.cancel_bill), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.keep_bill)) }
            },
        )
    }
}

@Composable
private fun ReceiptStatus(receipt: ReceiptUi) {
    val label = when (receipt.sale.status) {
        SaleStatus.CANCELLED -> stringResource(R.string.status_cancelled)
        SaleStatus.RETURNED -> stringResource(R.string.status_returned)
        SaleStatus.PARTIAL_RETURN -> stringResource(R.string.status_partial_return)
        SaleStatus.COMPLETED -> {
            val due = receipt.sale.totalPaise - receipt.sale.returnedPaise - receipt.sale.paidPaise
            stringResource(if (due > 0) R.string.receipt_status_due else R.string.receipt_status_paid)
        }
    }
    val isWarning = receipt.sale.status != SaleStatus.COMPLETED ||
        receipt.sale.totalPaise - receipt.sale.returnedPaise - receipt.sale.paidPaise > 0
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun AmountRow(label: String, paise: Long, emphasize: Boolean = false) {
    val style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = style)
        Text(Money(paise).format(), style = style, color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
