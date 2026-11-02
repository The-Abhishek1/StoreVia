package com.xcloak.storevia.feature.sales

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReturnScreen(onBack: () -> Unit, onDone: () -> Unit, vm: ReturnViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { onDone() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.return_items)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        if (!ui.loaded) return@Column

        if (ui.rows.none { it.returnableMilli > 0 }) {
            Text(stringResource(R.string.nothing_returnable), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(ui.rows, key = { it.productId }) { row ->
                ListItem(
                    headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(stringResource(R.string.sold_returned,
                            Parsing.milliToInput(row.soldMilli) + " " + row.symbol,
                            Parsing.milliToInput(row.returnedMilli) + " " + row.symbol))
                    },
                    trailingContent = {
                        OutlinedTextField(
                            value = ui.qtyText[row.productId].orEmpty(),
                            onValueChange = { vm.setQty(row.productId, it) },
                            enabled = row.returnableMilli > 0, singleLine = true,
                            placeholder = { Text("0") }, suffix = { Text(row.symbol) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(128.dp),
                        )
                    },
                )
                HorizontalDivider()
            }
        }

        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val pv = ui.preview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.return_value), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text(Money(pv?.totalPaise ?: 0).format(), style = MaterialTheme.typography.headlineSmall)
                }
                if (pv != null && pv.dueReducedPaise > 0) {
                    Text(stringResource(R.string.reduces_due, Money(pv.dueReducedPaise).format()), style = MaterialTheme.typography.bodyMedium)
                }
                if (pv != null && pv.refundPaise > 0) {
                    Text(stringResource(R.string.refund_to_customer, Money(pv.refundPaise).format()), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.refund_how), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PaymentMethod.CASH to R.string.pay_cash, PaymentMethod.UPI to R.string.pay_upi, PaymentMethod.CARD to R.string.pay_card)
                            .forEach { (m, label) ->
                                FilterChip(selected = !ui.asCredit && ui.method == m, onClick = { vm.setMethod(m) }, label = { Text(stringResource(label)) })
                            }
                        if (ui.hasCustomer) FilterChip(selected = ui.asCredit, onClick = vm::setCredit, label = { Text(stringResource(R.string.refund_credit)) })
                    }
                }
                ui.error?.let { Text(stringResource(it.res), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = vm::confirm, enabled = pv != null && !ui.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.return_confirm))
                }
            }
        }
    }
}
