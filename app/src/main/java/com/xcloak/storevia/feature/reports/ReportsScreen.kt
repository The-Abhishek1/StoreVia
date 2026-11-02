package com.xcloak.storevia.feature.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.dao.BatchAgg
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.util.Parsing

@Composable
fun ReportsScreen(vm: ReportsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s = ui.sales

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Period.TODAY to R.string.period_today, Period.LAST_7_DAYS to R.string.period_7_days, Period.THIS_MONTH to R.string.period_month)
                    .forEach { (p, label) -> FilterChip(selected = ui.period == p, onClick = { vm.setPeriod(p) }, label = { Text(stringResource(label)) }) }
            }
        }
        item {
            Column {
                Text(stringResource(R.string.net_sales), style = MaterialTheme.typography.labelLarge)
                Text(Money(s.netPaise).format(), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.net_sales_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.bills_count), s.bills.toString(), Modifier.weight(1f))
                Stat(stringResource(R.string.avg_bill), Money(s.averageBillPaise).format(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.discounts_given), Money(s.discountPaise).format(), Modifier.weight(1f))
                Stat(stringResource(R.string.returns_label), Money(s.returnsPaise).format(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.tax_collected), Money(s.taxPaise).format(), Modifier.weight(1f))
                Stat(stringResource(R.string.est_profit), Money(s.profitPaise).format(), Modifier.weight(1f))
            }
            Text(stringResource(R.string.profit_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        item { Section(R.string.money_received) }
        if (ui.payments.isEmpty()) item { Text(stringResource(R.string.no_payments), style = MaterialTheme.typography.bodyMedium) }
        else items(ui.payments.size) { i ->
            val p = ui.payments[i]
            Line(stringResource(methodLabel(p.method)), Money(p.totalPaise).format())
        }

        item { Section(R.string.top_products) }
        if (ui.top.isEmpty()) item { Text(stringResource(R.string.no_sales_period), style = MaterialTheme.typography.bodyMedium) }
        else items(ui.top.size) { i ->
            val t = ui.top[i]
            Line("${t.name} · ${Parsing.milliToInput(t.qtyMilli)} ${t.unitSymbol}".trim(), Money(t.revenuePaise).format())
        }

        val st = ui.stock
        item { Section(R.string.stock_value_title) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.at_cost), Money(st.costPaise).format(), Modifier.weight(1f))
                Stat(stringResource(R.string.at_retail), Money(st.retailPaise).format(), Modifier.weight(1f))
            }
        }

        item { Section(R.string.expiry_title) }
        item { ExpiryLine(R.string.expiring_30, st.expiring) }
        item { ExpiryLine(R.string.expired_stock, st.expired) }

        item { Section(R.string.low_stock_title) }
        if (st.lowStock.isEmpty()) item { Text(stringResource(R.string.low_stock_none), style = MaterialTheme.typography.bodyMedium) }
        else items(st.lowStock.size) { i ->
            val r = st.lowStock[i]
            Line(r.name, stringResource(R.string.low_stock_row, Parsing.milliToInput(r.onHandMilli), Parsing.milliToInput(r.minStockMilli)))
        }

        item { Section(R.string.khata_title) }
        item { Line(stringResource(R.string.total_khata_due), Money(st.khataDuePaise).format()) }
        item { Line(stringResource(R.string.you_owe_suppliers), Money(st.payablePaise).format()) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun methodLabel(m: PaymentMethod) = when (m) {
    PaymentMethod.CASH -> R.string.pay_cash
    PaymentMethod.UPI -> R.string.pay_upi
    PaymentMethod.CARD -> R.string.pay_card
    PaymentMethod.OTHER -> R.string.pay_other
}

@Composable
private fun Section(title: Int) {
    Column {
        HorizontalDivider(Modifier.padding(bottom = 12.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ExpiryLine(label: Int, agg: BatchAgg) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.batches_value, agg.batches, Money(agg.costPaise).format()), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
