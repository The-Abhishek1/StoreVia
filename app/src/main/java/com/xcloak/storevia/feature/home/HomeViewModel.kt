package com.xcloak.storevia.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.access.StaffSession
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.DailySalesSummary
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.prefs.StorageModeRouter
import io.github.jan.supabase.SupabaseClient
import kotlinx.serialization.json.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class HomeState(
    val todaySalesPaise: Long = 0,
    val todayCount: Int = 0,
    val weeklySales: List<HomeDaySales> = emptyList(),
    val previousWeekSalesPaise: Long = 0,
    val productCount: Int = 0,
    val lowStockCount: Int = 0,
    val expiringCount: Int = 0,
    val khataDuePaise: Long = 0,
    val expiryWarnDays: Int = 30,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class HomeDaySales(val day: LocalDate, val totalPaise: Long)

internal data class DashboardQuery(
    val branchId: String,
    val expiryWarnDays: Int,
    val salesTo: Long,
    val todayFrom: Long,
    val chartFrom: Long,
    val expiryFrom: LocalDate,
    val expiryTo: LocalDate,
    val today: LocalDate,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    bootstrap: AppBootstrap,
    saleDao: SaleDao,
    productDao: ProductDao,
    batchDao: BatchDao,
    customerDao: CustomerDao,
    storeDao: StoreDao,
    branchDao: BranchDao,
    prefs: AppPrefs,
    staffSession: StaffSession,
    private val supabase: SupabaseClient,
) : ViewModel() {

    init { viewModelScope.launch { bootstrap.ensureSeeded() } }

    private val placeholderStore: Flow<Boolean> = combine(storeDao.observe(), prefs.settings) { store, settings ->
        settings.storageMode != StorageMode.CLOUD && store != null && store.name == "My Store"
    }

    /** First run: open the onboarding flow. */
    val needsOnboarding: StateFlow<Boolean> = combine(placeholderStore, prefs.settings) { placeholder, s -> placeholder && !s.onboardingDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Onboarding was skipped but the shop still has the placeholder name: show a "finish setup" card. */
    val needsSetup: StateFlow<Boolean> = combine(placeholderStore, prefs.settings) { placeholder, s -> placeholder && s.onboardingDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val zone = ZoneId.systemDefault()
    private val activeBranchId = combine(branchDao.observeAll(), branchDao.observeSelectedId()) { branches, selectedId ->
        selectedId?.takeIf { id -> branches.any { it.id == id } } ?: branches.firstOrNull()?.id
    }.map { it ?: "default" }.distinctUntilChanged()

    private val currentDay = flow {
        while (true) {
            val today = LocalDate.now(zone)
            emit(today)
            val nextDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            delay((nextDay - System.currentTimeMillis()).coerceAtLeast(1L))
        }
    }.distinctUntilChanged()

    private val dashboardQuery = combine(
        activeBranchId,
        prefs.settings.map { it.expiryWarnDays.coerceAtLeast(1) }.distinctUntilChanged(),
        currentDay,
    ) { branchId, expiryDays, today ->
        DashboardQuery(
            branchId = branchId,
            expiryWarnDays = expiryDays,
            salesTo = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            todayFrom = today.atStartOfDay(zone).toInstant().toEpochMilli(),
            chartFrom = today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli(),
            expiryFrom = today,
            expiryTo = today.plusDays(expiryDays.toLong()),
            today = today,
        )
    }

    private val refreshes = MutableStateFlow(0)
    fun retry() { refreshes.update { it + 1 } }

    private val cloudSelection = prefs.settings
        .map { it.storageMode to it.cloudStoreId }
        .distinctUntilChanged()

    val state: StateFlow<HomeState> = combine(
        dashboardQuery,
        staffSession.actorState.map { it.can(Permission.VIEW_REPORTS) }.distinctUntilChanged(),
        cloudSelection,
        refreshes,
    ) { query, canViewReports, selection, _ -> DashboardRequest(query, canViewReports, selection.first, selection.second) }
        .flatMapLatest { request ->
            val query = request.query
            StorageModeRouter.route(
                request.storageMode,
                local = { localDashboard(query, request.canViewReports, saleDao, productDao, batchDao, customerDao) },
                cloud = {
                    flow {
                    emit(HomeState(expiryWarnDays = query.expiryWarnDays, isLoading = true))
                    val storeId = checkNotNull(request.cloudStoreId) { "No cloud store is selected." }
                    val rows = fetchCloudStoreRows(
                        supabase,
                        storeId,
                        setOf("sales", "products", "batches", "customers"),
                    )
                    emit(calculateCloudHomeState(rows, query, request.canViewReports))
                    }.catch { failure ->
                        emit(HomeState(
                            expiryWarnDays = query.expiryWarnDays,
                            error = failure.message ?: "Cloud dashboard could not be loaded.",
                        ))
                    }
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState(isLoading = true))
}

private data class DashboardRequest(
    val query: DashboardQuery,
    val canViewReports: Boolean,
    val storageMode: StorageMode?,
    val cloudStoreId: String?,
)

private fun localDashboard(
    query: DashboardQuery,
    canViewReports: Boolean,
    saleDao: SaleDao,
    productDao: ProductDao,
    batchDao: BatchDao,
    customerDao: CustomerDao,
): Flow<HomeState> {
        val salesFlow = if (canViewReports) {
            saleDao.observeDailySummaries(query.chartFrom, query.salesTo, query.branchId)
        } else {
            saleDao.observeSummary(query.todayFrom, query.salesTo, query.branchId).map { summary ->
                listOf(DailySalesSummary(query.today.toString(), summary.totalPaise, summary.count))
            }
        }
        return combine(
            salesFlow,
            productDao.observeStockedCount(query.branchId),
            batchDao.observeLowStock(query.branchId).map { it.size },
            batchDao.observeExpiringCount(query.expiryFrom, query.expiryTo, query.branchId),
            if (canViewReports) customerDao.observeBranchSalesDue(query.branchId) else flowOf(0L),
        ) { sales, products, lowStock, expiring, khataDue ->
            val salesByDay = sales.associateBy { LocalDate.parse(it.day) }
            val weeklySales = if (canViewReports) (6L downTo 0L).map { offset ->
                val day = query.today.minusDays(offset)
                HomeDaySales(day, salesByDay[day]?.totalPaise ?: 0L)
            } else emptyList()
            val previousWeekTotal = if (canViewReports) {
                (13L downTo 7L).sumOf { offset ->
                    salesByDay[query.today.minusDays(offset)]?.totalPaise ?: 0L
                }
            } else 0L
            val today = salesByDay[query.today]
            HomeState(
                todaySalesPaise = today?.totalPaise ?: 0L,
                todayCount = today?.count ?: 0,
                weeklySales = weeklySales,
                previousWeekSalesPaise = previousWeekTotal,
                productCount = products,
                lowStockCount = lowStock,
                expiringCount = expiring,
                khataDuePaise = khataDue,
                expiryWarnDays = query.expiryWarnDays,
            )
        }
        .onStart { emit(HomeState(expiryWarnDays = query.expiryWarnDays, isLoading = true)) }
        .catch { failure ->
            emit(HomeState(
                expiryWarnDays = query.expiryWarnDays,
                error = failure.message ?: "Dashboard could not be loaded.",
            ))
        }
}

internal fun calculateCloudHomeState(
    rows: Map<String, List<JsonObject>>,
    query: DashboardQuery,
    canViewReports: Boolean,
): HomeState {
    val sales = rows["sales"].orEmpty().filter { sale ->
        sale.cloudString("status") != "CANCELLED" &&
            sale.cloudString("branch_id") == query.branchId &&
            (sale.cloudLong("created_at") ?: Long.MIN_VALUE) >= query.chartFrom &&
            (sale.cloudLong("created_at") ?: Long.MAX_VALUE) < query.salesTo
    }
    val totalsByDay = sales.groupBy { row ->
        java.time.Instant.ofEpochMilli(row.cloudLong("created_at") ?: 0L)
            .atZone(ZoneId.systemDefault()).toLocalDate()
    }.mapValues { (_, rowsForDay) ->
        rowsForDay.sumOf { it.cloudLong("total_paise") ?: 0L } to rowsForDay.size
    }
    val products = rows["products"].orEmpty().filter { it.cloudLong("deleted_at") == null }
    val activeProducts = products.filter { it.cloudLong("is_active") != 0L }
    val activeParentIds = activeProducts.mapNotNull { it.cloudString("parent_product_id") }.toSet()
    val stockRows = rows["batches"].orEmpty().filter {
        it.cloudLong("deleted_at") == null && it.cloudString("branch_id") == query.branchId
    }
    val stockByProduct = stockRows.groupBy { it.cloudString("product_id") }
        .mapValues { (_, batches) -> batches.sumOf { it.cloudLong("qty_on_hand_milli") ?: 0L } }
    val sellableStockedCount = activeProducts.count { product ->
        product.cloudString("id") !in activeParentIds &&
            stockRows.any { it.cloudString("product_id") == product.cloudString("id") && (it.cloudLong("qty_on_hand_milli") ?: 0L) > 0L }
    }
    val lowStockCount = activeProducts.count { product ->
        product.cloudString("id") !in activeParentIds &&
            (product.cloudLong("min_stock_milli") ?: 0L) > 0L &&
            (stockByProduct[product.cloudString("id")] ?: 0L) < (product.cloudLong("min_stock_milli") ?: 0L)
    }
    val expiringCount = stockRows.count { batch ->
        val expiry = batch.cloudString("expiry_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        (batch.cloudLong("qty_on_hand_milli") ?: 0L) > 0L &&
            expiry != null && !expiry.isBefore(query.expiryFrom) && !expiry.isAfter(query.expiryTo)
    }
    val validCustomers = rows["customers"].orEmpty()
        .filter { it.cloudLong("deleted_at") == null }
        .mapNotNullTo(mutableSetOf()) { it.cloudString("id") }
    val khataDue = if (canViewReports) rows["sales"].orEmpty().sumOf { sale ->
        if (sale.cloudString("status") == "CANCELLED" ||
            sale.cloudString("branch_id") != query.branchId ||
            sale.cloudString("customer_id") !in validCustomers
        ) 0L else ((sale.cloudLong("total_paise") ?: 0L) -
            (sale.cloudLong("returned_paise") ?: 0L) -
            (sale.cloudLong("paid_paise") ?: 0L)).coerceAtLeast(0L)
    } else 0L
    val today = totalsByDay[query.today]

    return HomeState(
        todaySalesPaise = today?.first ?: 0L,
        todayCount = today?.second ?: 0,
        weeklySales = if (canViewReports) (6L downTo 0L).map { offset ->
            val day = query.today.minusDays(offset)
            HomeDaySales(day, totalsByDay[day]?.first ?: 0L)
        } else emptyList(),
        previousWeekSalesPaise = if (canViewReports) (13L downTo 7L).sumOf { offset ->
            totalsByDay[query.today.minusDays(offset)]?.first ?: 0L
        } else 0L,
        productCount = sellableStockedCount,
        lowStockCount = lowStockCount,
        expiringCount = expiringCount,
        khataDuePaise = khataDue,
        expiryWarnDays = query.expiryWarnDays,
    )
}
