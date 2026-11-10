package com.xcloak.storevia.feature.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.*
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.prefs.StorageModeRouter
import com.xcloak.storevia.feature.home.fetchCloudStoreRows
import io.github.jan.supabase.SupabaseClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

internal data class ReportQuery(
    val from: Long,
    val to: Long,
    val method: PaymentMethod?,
    val filters: ReportFilters,
    val period: Period,
)
private data class SelectedReportFilters(
    val period: Period,
    val method: PaymentMethod?,
    val filters: ReportFilters,
)
internal data class ReportSnapshot(
    val query: ReportQuery? = null,
    val sales: SalesReport = SalesReport(),
    val payments: List<PaymentTotalRow> = emptyList(),
    val top: List<TopProductRow> = emptyList(),
    val stock: StockReport = StockReport(),
    val purchaseSpendPaise: Long = 0,
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
    val isLoading: Boolean = false,
    val error: String? = null,
)

private data class ReportLoad(
    val query: ReportQuery? = null,
    val snapshot: ReportSnapshot? = null,
    val options: ReportFilterOptions = ReportFilterOptions(),
    val isLoading: Boolean = false,
    val error: String? = null,
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
    private val supabase: SupabaseClient,
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
    private val currentDay = flow {
        while (true) {
            val today = LocalDate.now(zone)
            emit(today)
            val nextDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            delay((nextDay - System.currentTimeMillis()).coerceAtLeast(1L))
        }
    }.distinctUntilChanged()

    private val reportRange = combine(period, _customRange, currentDay) { p, custom, today ->
        reportDateBounds(p, today, custom, zone)
    }.distinctUntilChanged()

    private val query = combine(reportRange, paymentMethod, filters, period) { range, method, selected, selectedPeriod ->
        ReportQuery(range.first, range.second, method, selected, selectedPeriod)
    }.distinctUntilChanged()

    private val expiryDays = prefs.settings
        .map { it.expiryWarnDays.coerceAtLeast(1) }
        .distinctUntilChanged()

    private val selectedFilters = combine(period, paymentMethod, filters) { p, method, selected ->
        SelectedReportFilters(p, method, selected)
    }
    private val refreshes = MutableStateFlow(0)
    fun retry() { refreshes.update { it + 1 } }

    private val storageSelection = prefs.settings
        .map { it.storageMode to it.cloudStoreId }
        .distinctUntilChanged()

    private val reportLoad: StateFlow<ReportLoad> = combine(
        query,
        expiryDays,
        currentDay,
        storageSelection,
        refreshes,
    ) { q, days, today, selection, _ ->
        ReportRequest(q, days, today, selection.first, selection.second)
    }.flatMapLatest { request ->
        StorageModeRouter.route(
            request.storageMode,
            local = {
                localReportLoad(
                    request,
                    reports,
                    batchDao,
                    customerDao,
                    supplierDao,
                    productDao,
                    categoryDao,
                    branchDao,
                )
            },
            cloud = {
                flow {
                    emit(ReportLoad(query = request.query, isLoading = true))
                    val storeId = checkNotNull(request.cloudStoreId) { "No cloud store is selected." }
                    val rows = fetchCloudStoreRows(
                        supabase,
                        storeId,
                        setOf(
                            "sales", "sale_lines", "payments", "sale_returns", "sale_return_lines",
                            "products", "categories", "batches", "units", "customers", "customer_ledger",
                            "suppliers", "supplier_ledger", "purchases", "purchase_lines", "branches",
                        ),
                    )
                    val result = calculateCloudReports(rows, request.query, request.today, request.expiryWarnDays)
                    emit(ReportLoad(request.query, result.snapshot, result.options))
                }.catch { failure ->
                    emit(ReportLoad(
                        query = request.query,
                        error = failure.message ?: "Cloud reports could not be loaded.",
                    ))
                }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportLoad(isLoading = true))

    init {
        viewModelScope.launch {
            reportLoad.collect { loaded ->
                if (loaded.isLoading || loaded.error != null) return@collect
                val available = loaded.options
                val productIds = available.products.mapTo(mutableSetOf()) { it.id }
                val categoryIds = available.categories.mapTo(mutableSetOf()) { it.id }
                val customerIds = available.customers.mapTo(mutableSetOf()) { it.id }
                val supplierIds = available.suppliers.mapTo(mutableSetOf()) { it.id }
                val branchIds = available.branches.mapTo(mutableSetOf()) { it.id }
                val taxRates = available.taxRatesBp.toSet()
                filters.update { current ->
                    current.copy(
                        productId = current.productId?.takeIf(productIds::contains),
                        categoryId = current.categoryId?.takeIf(categoryIds::contains),
                        customerId = current.customerId?.takeIf(customerIds::contains),
                        supplierId = current.supplierId?.takeIf(supplierIds::contains),
                        branchId = current.branchId?.takeIf(branchIds::contains),
                        taxRateBp = current.taxRateBp?.takeIf(taxRates::contains),
                    )
                }
            }
        }
    }

    val ui: StateFlow<ReportsUi> = combine(reportLoad, selectedFilters) { loaded, selected ->
        val query = loaded.query
        val snapshot = loaded.snapshot?.takeIf {
            it.query == query && query?.period == selected.period &&
                query.method == selected.method && query.filters == selected.filters
        }
        ReportsUi(
            period = selected.period,
            paymentMethod = selected.method,
            filters = selected.filters,
            filterOptions = loaded.options,
            sales = snapshot?.sales ?: SalesReport(),
            payments = snapshot?.payments ?: emptyList(),
            top = snapshot?.top ?: emptyList(),
            stock = snapshot?.stock ?: StockReport(),
            purchaseSpendPaise = snapshot?.purchaseSpendPaise ?: 0,
            isLoading = loaded.isLoading,
            error = loaded.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUi(isLoading = true))
}

private data class ReportRequest(
    val query: ReportQuery,
    val expiryWarnDays: Int,
    val today: LocalDate,
    val storageMode: StorageMode?,
    val cloudStoreId: String?,
)

private fun localReportLoad(
    request: ReportRequest,
    reports: ReportDao,
    batchDao: BatchDao,
    customerDao: CustomerDao,
    supplierDao: SupplierDao,
    productDao: ProductDao,
    categoryDao: CategoryDao,
    branchDao: BranchDao,
): Flow<ReportLoad> {
    val q = request.query
    val sales = combine(
        reports.sales(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
        reports.lines(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
        reports.returnsTotal(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
        reports.returnLines(q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId),
    ) { totals, lines, returns, returnLines ->
        SalesReport(
            bills = totals.bills,
            grossPaise = totals.grossPaise,
            returnsPaise = returns,
            discountPaise = totals.discountPaise,
            taxPaise = lines.taxPaise,
            profitPaise = (lines.taxablePaise - lines.costPaise) -
                (returnLines.taxablePaise - returnLines.costPaise),
        )
    }
    val payments = reports.paymentsByMethod(
        q.from, q.to, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId,
    )
    val topProducts = reports.topProducts(
        q.from, q.to, 5, q.method?.name, q.filters.productId, q.filters.categoryId, q.filters.customerId, q.filters.taxRateBp, q.filters.supplierId, q.filters.branchId,
    )
    val purchaseSpend = reports.purchaseSpend(q.from, q.to, q.filters.supplierId, q.filters.branchId)
    val stockBase = combine(
        reports.stockValue(q.filters.branchId),
        reports.expiryBetween(request.today, request.today.plusDays(request.expiryWarnDays.toLong()), q.filters.branchId),
        reports.expiryBetween(LocalDate.of(1970, 1, 1), request.today.minusDays(1), q.filters.branchId),
        batchDao.observeLowStock(q.filters.branchId),
        customerDao.observeTotalDue(),
    ) { value, expiring, expired, low, khata ->
        StockReport(value.costPaise, value.retailPaise, expiring, expired, low, khata)
    }
    val stock = combine(stockBase, supplierDao.observeTotalPayable()) { report, payable ->
        report.copy(payablePaise = payable)
    }
    val snapshot = combine(sales, payments, topProducts, purchaseSpend) { totals, paymentRows, products, purchases ->
        ReportSnapshot(
            query = q,
            sales = totals,
            payments = paymentRows,
            top = products,
            purchaseSpendPaise = purchases,
        )
    }
    val withStock = combine(snapshot, stock) { data, stockReport -> data.copy(stock = stockReport) }
    val options = combine(
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
    return combine(withStock, options) { current, choices -> ReportLoad(q, current, choices) }
        .onStart { emit(ReportLoad(query = q, isLoading = true)) }
        .catch { failure ->
            emit(ReportLoad(query = q, error = failure.message ?: "Reports could not be loaded."))
        }
}

private fun ProductEntity.filterOption(): ReportFilterOption =
    ReportFilterOption(id, listOfNotNull(name, variantLabel).joinToString(" · "))
