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
    val productCount: Int = 0,
    val lowStockCount: Int = 0,
    val expiringCount: Int = 0,
    val khataDuePaise: Long = 0,
    val expiryWarnDays: Int = 30,
)

private data class DashboardQuery(
    val branchId: String,
    val expiryWarnDays: Int,
    val salesFrom: Long,
    val salesTo: Long,
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
            salesFrom = today.atStartOfDay(zone).toInstant().toEpochMilli(),
            salesTo = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            expiryFrom = today,
            expiryTo = today.plusDays(expiryDays.toLong()),
        )
    }

    val state: StateFlow<HomeState> = dashboardQuery.flatMapLatest { query ->
        combine(
            saleDao.observeSummary(query.salesFrom, query.salesTo, query.branchId),
            productDao.observeCount(),
            batchDao.observeLowStock(query.branchId).map { it.size },
            batchDao.observeExpiringCount(query.expiryFrom, query.expiryTo, query.branchId),
            customerDao.observeTotalDue(),
        ) { sales, products, lowStock, expiring, khataDue ->
            HomeState(
                todaySalesPaise = sales.totalPaise,
                todayCount = sales.count,
                productCount = products,
                lowStockCount = lowStock,
                expiringCount = expiring,
                khataDuePaise = khataDue,
                expiryWarnDays = query.expiryWarnDays,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
