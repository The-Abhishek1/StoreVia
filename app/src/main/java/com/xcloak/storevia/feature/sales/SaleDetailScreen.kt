package com.xcloak.storevia.feature.sales

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleDetailScreen(onBack: () -> Unit, onReturn: (String) -> Unit, vm: SaleDetailViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val r = ui
    var confirmCancel by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.receipt)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (r != null) IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, r.shareText) }
                    context.startActivity(Intent.createChooser(send, null))
                }) { Icon(Icons.Filled.Share, stringResource(R.string.share_receipt)) }
            },
        )
        if (r == null) return@Column

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.store.name, style = MaterialTheme.typography.titleLarge)
            r.store.gstin?.let { Text(stringResource(R.string.gstin_label, it), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.invoice_label, r.sale.invoiceNo), style = MaterialTheme.typography.bodyMedium)
            r.customerName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            when (r.sale.status) {
                com.xcloak.storevia.core.database.entity.SaleStatus.CANCELLED -> Text(
                    r.sale.cancelReason?.let { stringResource(R.string.cancelled_reason, it) } ?: stringResource(R.string.status_cancelled),
                    color = MaterialTheme.colorScheme.error)
                com.xcloak.storevia.core.database.entity.SaleStatus.RETURNED -> Text(stringResource(R.string.status_returned), color = MaterialTheme.colorScheme.error)
                com.xcloak.storevia.core.database.entity.SaleStatus.PARTIAL_RETURN -> Text(stringResource(R.string.status_partial_return), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            r.items.forEach { i ->
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(i.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${Parsing.milliToInput(i.qtyMilli)} × ${Money(i.unitPricePaise).format()}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(Money(i.amountPaise).format(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            val cgst = r.lines.sumOf { it.cgstPaise }; val sgst = r.lines.sumOf { it.sgstPaise }; val igst = r.lines.sumOf { it.igstPaise }
            AmountRow(stringResource(R.string.taxable_value), r.sale.subtotalPaise)
            if (igst > 0) AmountRow("IGST", igst) else if (cgst + sgst > 0) { AmountRow("CGST", cgst); AmountRow("SGST", sgst) }
            if (r.sale.discountPaise > 0) AmountRow(stringResource(R.string.discount), r.sale.discountPaise)
            if (r.sale.roundOffPaise != 0L) AmountRow(stringResource(R.string.round_off), r.sale.roundOffPaise)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.total), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(Money(r.sale.totalPaise).format(), style = MaterialTheme.typography.headlineSmall)
            }
            if (r.sale.returnedPaise > 0) AmountRow(stringResource(R.string.returned_label), -r.sale.returnedPaise)
            r.payments.forEach { p -> AmountRow(stringResource(R.string.payment_via, stringResource(R.string.paid), p.method.name.lowercase()), p.amountPaise) }
            val due = r.sale.totalPaise - r.sale.returnedPaise - r.sale.paidPaise
            if (due > 0) AmountRow(stringResource(R.string.balance_due), due)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.canReturn && can(Permission.PROCESS_RETURN)) OutlinedButton(onClick = { onReturn(r.sale.id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.return_items))
            }
            if (r.canCancel && can(Permission.CANCEL_BILL)) TextButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel_bill), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.done)) }
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
                    OutlinedTextField(reason, { reason = it }, singleLine = true, label = { Text(stringResource(R.string.field_cancel_reason)) })
                }
            },
            confirmButton = { TextButton(onClick = { confirmCancel = false; vm.cancel(reason) }) { Text(stringResource(R.string.cancel_bill), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.keep_bill)) } },
        )
    }
}

@Composable
private fun AmountRow(label: String, paise: Long) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(Money(paise).format(), style = MaterialTheme.typography.bodyMedium)
    }
}
