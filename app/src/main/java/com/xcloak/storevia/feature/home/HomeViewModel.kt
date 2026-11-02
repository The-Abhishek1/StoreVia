package com.xcloak.storevia.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.data.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    bootstrap: AppBootstrap,
    saleDao: SaleDao,
    productDao: ProductDao,
    batchDao: BatchDao,
    customerDao: CustomerDao,
    storeDao: StoreDao,
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
    private val today = LocalDate.now()
    private val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val state: StateFlow<HomeState> = combine(
        saleDao.observeSummary(from, to),
        productDao.observeCount(),
        batchDao.observeLowStock().map { it.size },
        batchDao.observeExpiringCount(today, today.plusDays(30)),
        customerDao.observeTotalDue(),
    ) { s, products, low, expiring, khata ->
        HomeState(s.totalPaise, s.count, products, low, expiring, khata)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}
