package com.xcloak.storevia.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.*
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.data.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class Period { TODAY, LAST_7_DAYS, THIS_MONTH, CUSTOM }
data class ReportDateRange(val start: LocalDate, val endInclusive: LocalDate)
data class ReportFilterOption(val id: String, val label: String)
data class ReportFilters(
    val productId: String? = null,
    val categoryId: String? = null,
    val customerId: String? = null,
    val taxRateBp: Int? = null,
    val supplierId: String? = null,
    val branchId: String? = null,
)
data class ReportFilterOptions(
    val products: List<ReportFilterOption> = emptyList(),
    val categories: List<ReportFilterOption> = emptyList(),
    val customers: List<ReportFilterOption> = emptyList(),
    val taxRatesBp: List<Int> = emptyList(),
    val suppliers: List<ReportFilterOption> = emptyList(),
    val branches: List<ReportFilterOption> = emptyList(),
)

private data class ReportQuery(
    val from: Long,
    val to: Long,
    val method: PaymentMethod?,
    val filters: ReportFilters,
)
private data class SelectedReportFilters(
    val period: Period,
    val method: PaymentMethod?,
    val filters: ReportFilters,
)

internal fun reportDateBounds(
    period: Period,
    today: LocalDate,
    custom: ReportDateRange?,
    zone: ZoneId,
): Pair<Long, Long> {
    val start = when (period) {
        Period.TODAY -> today
        Period.LAST_7_DAYS -> today.minusDays(6)
        Period.THIS_MONTH -> today.withDayOfMonth(1)
        Period.CUSTOM -> custom?.start ?: today
    }
    val endExclusive = when (period) {
        Period.CUSTOM -> (custom?.endInclusive ?: today).plusDays(1)
        else -> today.plusDays(1)
    }
    return start.atStartOfDay(zone).toInstant().toEpochMilli() to
        endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
}

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
    val paymentMethod: PaymentMethod? = null,
    val filters: ReportFilters = ReportFilters(),
    val filterOptions: ReportFilterOptions = ReportFilterOptions(),
    val sales: SalesReport = SalesReport(),
    val payments: List<PaymentTotalRow> = emptyList(),
    val top: List<TopProductRow> = emptyList(),
    val stock: StockReport = StockReport(),
    val purchaseSpendPaise: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reports: ReportDao,
    batchDao: BatchDao,
    customerDao: CustomerDao,
    supplierDao: SupplierDao,
    productDao: ProductDao,
    categoryDao: CategoryDao,
    branchDao: BranchDao,
    prefs: AppPrefs,
) : ViewModel() {

    private val period = MutableStateFlow(Period.TODAY)
    private val paymentMethod = MutableStateFlow<PaymentMethod?>(null)
    private val filters = MutableStateFlow(ReportFilters())
    private val _customRange = MutableStateFlow<ReportDateRange?>(null)
    val customRange: StateFlow<ReportDateRange?> = _customRange.asStateFlow()

    fun setPeriod(p: Period) {
        if (p == Period.CUSTOM && _customRange.value == null) {
            val today = LocalDate.now()
            _customRange.value = ReportDateRange(today, today)
        }
        period.value = p
    }

    fun setPaymentMethod(method: PaymentMethod?) {
        paymentMethod.value = method
    }

    fun setProductFilter(id: String?) = filters.update { it.copy(productId = id) }
    fun setCategoryFilter(id: String?) = filters.update { it.copy(categoryId = id) }
    fun setCustomerFilter(id: String?) = filters.update { it.copy(customerId = id) }
    fun setTaxRateFilter(rateBp: Int?) = filters.update { it.copy(taxRateBp = rateBp) }
    fun setSupplierFilter(id: String?) = filters.update { it.copy(supplierId = id) }
    fun setBranchFilter(id: String?) = filters.update { it.copy(branchId = id) }

    fun setCustomRange(start: LocalDate, endInclusive: LocalDate): Boolean {
        if (endInclusive.isBefore(start)) return false
        _customRange.value = ReportDateRange(start, endInclusive)
        period.value = Period.CUSTOM
        return true
    }

    private val zone = ZoneId.systemDefault()
    private val reportRange = combine(period, _customRange) { p, custom ->
        reportDateBounds(p, LocalDate.now(), custom, zone)
    }.distinctUntilChanged()

    private val query = combine(reportRange, paymentMethod, filters) { range, method, selected ->
        ReportQuery(range.first, range.second, method, selected)
    }.distinctUntilChanged()

    private val salesFlow: Flow<SalesReport> = query.flatMapLatest { q ->
        combine(
            reports.sales(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
            reports.lines(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
            reports.returnsTotal(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
            reports.returnLines(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
        ) { sales, lines, returns, retLines ->
            SalesReport(
                bills = sales.bills, grossPaise = sales.grossPaise, returnsPaise = returns, discountPaise = sales.discountPaise,
                taxPaise = lines.taxPaise,
                // Estimated gross profit: revenue excluding tax minus cost, less what was returned.
                profitPaise = (lines.taxablePaise - lines.costPaise) - (retLines.taxablePaise - retLines.costPaise),
            )
        }
    }

    private val stockFlow: Flow<StockReport> = combine(
        prefs.settings.map { it.expiryWarnDays.coerceAtLeast(1) }.distinctUntilChanged(),
        filters.map { it.branchId }.distinctUntilChanged(),
    ) { expiryDays, branchId -> expiryDays to branchId }
        .distinctUntilChanged()
        .flatMapLatest { (expiryDays, branchId) ->
            val today = LocalDate.now()
            val base = combine(
                reports.stockValue(branchId),
                reports.expiryBetween(today, today.plusDays(expiryDays.toLong()), branchId),
                reports.expiryBetween(LocalDate.of(1970, 1, 1), today.minusDays(1), branchId),
                batchDao.observeLowStock(branchId),
                customerDao.observeTotalDue(),
            ) { value, expiring, expired, low, khata ->
                StockReport(value.costPaise, value.retailPaise, expiring, expired, low, khata)
            }
            combine(base, supplierDao.observeTotalPayable()) { st, payable -> st.copy(payablePaise = payable) }
        }

    private val paymentsFlow = query.flatMapLatest { q ->
        reports.paymentsByMethod(
            q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId,
        )
    }
    private val topProductsFlow = query.flatMapLatest { q ->
        reports.topProducts(
            q.from, q.to, 5, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId,
        )
    }
    private val purchaseSpendFlow = query.flatMapLatest { q ->
        reports.purchaseSpend(q.from, q.to, q.filters.supplierId, q.filters.branchId)
    }
    private val selectedFilters = combine(period, paymentMethod, filters) { p, method, selected ->
        SelectedReportFilters(p, method, selected)
    }
    private val options = combine(
        productDao.observeActiveSellable(),
        categoryDao.observeAll(),
        customerDao.observeAll(),
        supplierDao.observeAll(),
        branchDao.observeAll(),
    ) { products, categories, customers, suppliers, branches ->
        ReportFilterOptions(
            products = products.map { it.filterOption() },
            categories = categories.map { ReportFilterOption(it.id, it.name) },
            customers = customers.map { ReportFilterOption(it.id, it.name) },
            taxRatesBp = products.map { it.gstRateBp }.distinct().sorted(),
            suppliers = suppliers.map { ReportFilterOption(it.id, it.name) },
            branches = branches.map { ReportFilterOption(it.id, it.name) },
        )
    }

    private val reportBase = combine(
        selectedFilters,
        salesFlow,
        paymentsFlow,
        topProductsFlow,
        stockFlow,
    ) { filters, sales, pay, top, stock ->
        ReportsUi(
            filters.period, filters.method, filters.filters,
            sales = sales, payments = pay, top = top, stock = stock,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUi())

    private val reportUi = combine(reportBase, purchaseSpendFlow) { current, purchaseSpend ->
        current.copy(purchaseSpendPaise = purchaseSpend)
    }

    val ui: StateFlow<ReportsUi> = combine(reportUi, options) { current, choices ->
        current.copy(filterOptions = choices)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUi())
}

private fun ProductEntity.filterOption(): ReportFilterOption =
    ReportFilterOption(id, listOfNotNull(name, variantLabel).joinToString(" · "))
