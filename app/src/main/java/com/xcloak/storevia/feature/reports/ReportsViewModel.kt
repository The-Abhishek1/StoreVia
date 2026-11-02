package com.xcloak.storevia.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class Period { TODAY, LAST_7_DAYS, THIS_MONTH }

data class SalesReport(
    val bills: Int = 0,
    val grossPaise: Long = 0,
    val returnsPaise: Long = 0,
    val discountPaise: Long = 0,
    val taxPaise: Long = 0,
    val profitPaise: Long = 0,
) {
    val netPaise: Long get() = grossPaise - returnsPaise
    val averageBillPaise: Long get() = if (bills == 0) 0 else grossPaise / bills
}

data class StockReport(
    val costPaise: Long = 0,
    val retailPaise: Long = 0,
    val expiring: BatchAgg = BatchAgg(0, 0),
    val expired: BatchAgg = BatchAgg(0, 0),
    val lowStock: List<LowStockRow> = emptyList(),
    val khataDuePaise: Long = 0,
    val payablePaise: Long = 0,
)

data class ReportsUi(
    val period: Period = Period.TODAY,
    val sales: SalesReport = SalesReport(),
    val payments: List<PaymentTotalRow> = emptyList(),
    val top: List<TopProductRow> = emptyList(),
    val stock: StockReport = StockReport(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reports: ReportDao,
    batchDao: BatchDao,
    customerDao: CustomerDao,
    supplierDao: SupplierDao,
) : ViewModel() {

    private val period = MutableStateFlow(Period.TODAY)
    fun setPeriod(p: Period) { period.value = p }

    private val zone = ZoneId.systemDefault()
    private fun millis(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun range(p: Period, today: LocalDate): Pair<Long, Long> {
        val start = when (p) {
            Period.TODAY -> today
            Period.LAST_7_DAYS -> today.minusDays(6)
            Period.THIS_MONTH -> today.withDayOfMonth(1)
        }
        return millis(start) to millis(today.plusDays(1))
    }

    private val salesFlow: Flow<SalesReport> = period.flatMapLatest { p ->
        val (from, to) = range(p, LocalDate.now())
        combine(
            reports.sales(from, to), reports.lines(from, to), reports.returnsTotal(from, to), reports.returnLines(from, to),
        ) { sales, lines, returns, retLines ->
            SalesReport(
                bills = sales.bills, grossPaise = sales.grossPaise, returnsPaise = returns, discountPaise = sales.discountPaise,
                taxPaise = lines.taxPaise,
                // Estimated gross profit: revenue excluding tax minus cost, less what was returned.
                profitPaise = (lines.taxablePaise - lines.costPaise) - (retLines.taxablePaise - retLines.costPaise),
            )
        }
    }

    private val stockFlow: Flow<StockReport> = run {
        val today = LocalDate.now()
        val base = combine(
            reports.stockValue(),
            reports.expiryBetween(today, today.plusDays(30)),
            reports.expiryBetween(LocalDate.of(1970, 1, 1), today.minusDays(1)),
            batchDao.observeLowStock(),
            customerDao.observeTotalDue(),
        ) { value, expiring, expired, low, khata ->
            StockReport(value.costPaise, value.retailPaise, expiring, expired, low, khata)
        }
        combine(base, supplierDao.observeTotalPayable()) { st, payable -> st.copy(payablePaise = payable) }
    }

    val ui: StateFlow<ReportsUi> = combine(
        period,
        salesFlow,
        period.flatMapLatest { p -> val (f, t) = range(p, LocalDate.now()); reports.paymentsByMethod(f, t) },
        period.flatMapLatest { p -> val (f, t) = range(p, LocalDate.now()); reports.topProducts(f, t, 5) },
        stockFlow,
    ) { p, sales, pay, top, stock -> ReportsUi(p, sales, pay, top, stock) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUi())
}
