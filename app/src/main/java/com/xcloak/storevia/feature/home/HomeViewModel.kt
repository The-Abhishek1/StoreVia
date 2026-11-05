package com.xcloak.storevia.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.data.prefs.AppPrefs
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
)

data class HomeDaySales(val day: LocalDate, val totalPaise: Long)

private data class DashboardQuery(
    val branchId: String,
    val expiryWarnDays: Int,
    val salesTo: Long,
    val chartFrom: Long,
    val expiryFrom: LocalDate,
    val expiryTo: LocalDate,
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
) : ViewModel() {

    init { viewModelScope.launch { bootstrap.ensureSeeded() } }

    private val placeholderStore: Flow<Boolean> = storeDao.observe().map { it != null && it.name == "My Store" }

    /** First run: open the onboarding flow. */
    val needsOnboarding: StateFlow<Boolean> = combine(placeholderStore, prefs.settings) { placeholder, s -> placeholder && !s.onboardingDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Onboarding was skipped but the shop still has the placeholder name: show a "finish setup" card. */
    val needsSetup: StateFlow<Boolean> = combine(placeholderStore, prefs.settings) { placeholder, s -> placeholder && s.onboardingDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val zone = ZoneId.systemDefault()
    private val activeBranchId = combine(branchDao.observeAll(), branchDao.observeSelectedId()) { branches, selectedId ->
        selectedId?.takeIf { id -> branches.any { it.id == id } } ?: branches.firstOrNull()?.id
    }.filterNotNull().distinctUntilChanged()

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
            chartFrom = today.minusDays(13).atStartOfDay(zone).toInstant().toEpochMilli(),
            expiryFrom = today,
            expiryTo = today.plusDays(expiryDays.toLong()),
        )
    }

    val state: StateFlow<HomeState> = dashboardQuery.flatMapLatest { query ->
        combine(
            saleDao.observeDailySummaries(query.chartFrom, query.salesTo, query.branchId),
            productDao.observeCount(),
            batchDao.observeLowStock(query.branchId).map { it.size },
            batchDao.observeExpiringCount(query.expiryFrom, query.expiryTo, query.branchId),
            customerDao.observeTotalDue(),
        ) { sales, products, lowStock, expiring, khataDue ->
            val salesByDay = sales.associateBy { LocalDate.parse(it.day) }
            val weeklySales = (6L downTo 0L).map { offset ->
                val day = LocalDate.now(zone).minusDays(offset)
                HomeDaySales(day, salesByDay[day]?.totalPaise ?: 0L)
            }
            val previousWeekTotal = (13L downTo 7L).sumOf { offset ->
                salesByDay[LocalDate.now(zone).minusDays(offset)]?.totalPaise ?: 0L
            }
            val today = salesByDay[LocalDate.now(zone)]
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
