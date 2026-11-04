package com.xcloak.storevia.feature.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun ReportsScreen(vm: ReportsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val customRange by vm.customRange.collectAsStateWithLifecycle()
    val s = ui.sales
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var draftStart by remember { mutableStateOf<LocalDate?>(null) }
    var invalidRange by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Period.TODAY to R.string.period_today, Period.LAST_7_DAYS to R.string.period_7_days, Period.THIS_MONTH to R.string.period_month)
                    .forEach { (p, label) -> FilterChip(selected = ui.period == p, onClick = { vm.setPeriod(p) }, label = { Text(stringResource(label)) }) }
                FilterChip(
                    selected = ui.period == Period.CUSTOM,
                    onClick = {
                        vm.setPeriod(Period.CUSTOM)
                        draftStart = customRange?.start ?: LocalDate.now()
                        invalidRange = false
                        showStartPicker = true
                    },
                    label = {
                        val range = customRange
                        Text(
                            if (range == null) stringResource(R.string.period_custom)
                            else stringResource(R.string.report_custom_dates, range.start.toString(), range.endInclusive.toString()),
                        )
                    },
                )
            }
            if (invalidRange) Text(stringResource(R.string.report_invalid_date_range), color = MaterialTheme.colorScheme.error)
        }
        item {
            Text(stringResource(R.string.report_payment_filter), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.paymentMethod == null,
                    onClick = { vm.setPaymentMethod(null) },
                    label = { Text(stringResource(R.string.report_all_payment_modes)) },
                )
                PaymentMethod.entries.forEach { method ->
                    FilterChip(
                        selected = ui.paymentMethod == method,
                        onClick = { vm.setPaymentMethod(method) },
                        label = { Text(stringResource(methodLabel(method))) },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.report_entity_filters), style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = {
                        vm.setProductFilter(null)
                        vm.setCategoryFilter(null)
                        vm.setCustomerFilter(null)
                        vm.setTaxRateFilter(null)
                        vm.setSupplierFilter(null)
                        vm.setBranchFilter(null)
                    },
                    enabled = ui.filters != ReportFilters(),
                ) { Text(stringResource(R.string.report_clear_filters)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportFilterDropdown(
                    label = stringResource(R.string.report_filter_branch),
                    allLabel = stringResource(R.string.report_filter_all_branches),
                    selectedId = ui.filters.branchId,
                    options = ui.filterOptions.branches,
                    onSelect = vm::setBranchFilter,
                )
                ReportFilterDropdown(
                    label = stringResource(R.string.report_filter_product),
                    allLabel = stringResource(R.string.report_filter_all),
                    selectedId = ui.filters.productId,
                    options = ui.filterOptions.products,
                    onSelect = vm::setProductFilter,
                )
                ReportFilterDropdown(
                    label = stringResource(R.string.report_filter_category),
                    allLabel = stringResource(R.string.report_filter_all),
                    selectedId = ui.filters.categoryId,
                    options = ui.filterOptions.categories,
                    onSelect = vm::setCategoryFilter,
                )
                ReportFilterDropdown(
                    label = stringResource(R.string.report_filter_customer),
                    allLabel = stringResource(R.string.report_filter_all),
                    selectedId = ui.filters.customerId,
                    options = ui.filterOptions.customers,
                    onSelect = vm::setCustomerFilter,
                )
                ReportFilterDropdown(
                    label = stringResource(R.string.report_filter_tax),
                    allLabel = stringResource(R.string.report_filter_all),
                    selectedId = ui.filters.taxRateBp?.toString(),
                    options = ui.filterOptions.taxRatesBp.map {
                        ReportFilterOption(
                            it.toString(),
                            stringResource(R.string.report_tax_rate, taxRateLabel(it)),
                        )
                    },
                    onSelect = { vm.setTaxRateFilter(it?.toIntOrNull()) },
                )
                ReportFilterDropdown(
                    label = stringResource(R.string.report_filter_supplier),
                    allLabel = stringResource(R.string.report_filter_all),
                    selectedId = ui.filters.supplierId,
                    options = ui.filterOptions.suppliers,
                    onSelect = vm::setSupplierFilter,
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.net_sales), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(Money(s.netPaise).format(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(R.string.net_sales_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
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

        item { Section(R.string.report_supplier_purchases) }
        item {
            Line(
                stringResource(R.string.report_supplier_purchases_period),
                Money(ui.purchaseSpendPaise).format(),
            )
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

    if (showStartPicker) {
        val initial = (customRange?.start ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    draftStart = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showStartPicker = false
                    showEndPicker = true
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showStartPicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (showEndPicker) {
        val initial = (customRange?.endInclusive ?: draftStart ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showEndPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val end = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    val start = draftStart ?: LocalDate.now()
                    if (end != null && !vm.setCustomRange(start, end)) invalidRange = true
                    else invalidRange = false
                    showEndPicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showEndPicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportFilterDropdown(
    label: String,
    allLabel: String,
    selectedId: String?,
    options: List<ReportFilterOption>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.id == selectedId }?.label ?: allLabel
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = { onSelect(null); expanded = false },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option.id); expanded = false },
                )
            }
        }
    }
}

private fun taxRateLabel(rateBp: Int): String =
    if (rateBp % 100 == 0) (rateBp / 100).toString() else "%.2f".format(rateBp / 100.0)

private fun methodLabel(m: PaymentMethod) = when (m) {
    PaymentMethod.CASH -> R.string.pay_cash
    PaymentMethod.UPI -> R.string.pay_upi
    PaymentMethod.CARD -> R.string.pay_card
    PaymentMethod.OTHER -> R.string.pay_other
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Line(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ExpiryLine(label: Int, agg: BatchAgg) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.batches_value, agg.batches, Money(agg.costPaise).format()), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
