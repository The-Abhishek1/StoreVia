package com.xcloak.storevia.feature.customers

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.CustomerBalanceRow
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.util.Parsing
import com.xcloak.storevia.data.cloud.CloudPartyRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageModeRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val customerDao: CustomerDao,
    private val access: Access,
    private val cloud: CloudPartyRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val rows: StateFlow<List<CustomerBalanceRow>?> = combine(_query, prefs.settings) { query, settings ->
        query.trim() to settings.storageMode
    }.flatMapLatest { (query, mode) ->
            StorageModeRouter.route(mode, local = { customerDao.observeWithBalance(query) }, cloud = { cloud.observeCustomers(query) })
                .map<List<CustomerBalanceRow>, List<CustomerBalanceRow>?> { it }
                .onStart { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalDue: StateFlow<Long> = StorageModeRouter.observe(
        prefs.settings.map { it.storageMode },
        local = { customerDao.observeTotalDue() },
        cloud = { cloud.observeTotalCustomerDue() },
    )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setQuery(q: String) { _query.value = q }

    /** @return false if the credit limit text is invalid. */
    suspend fun add(name: String, phone: String, limitText: String): Boolean {
        if (name.isBlank() || !access.actor().can(Permission.MANAGE_CUSTOMERS)) return false
        val limit = if (limitText.isBlank()) null else (Parsing.moneyToPaise(limitText) ?: return false)
        if (limit != null && !access.actor().can(Permission.EDIT_CREDIT_LIMIT)) return false
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS) ||
            (limit != null && !access.actor().can(Permission.EDIT_CREDIT_LIMIT))
        ) return false
        val customer = CustomerEntity(name = name.trim(), phone = phone.trim().ifEmpty { null }, creditLimitPaise = limit)
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { customerDao.insert(customer) },
            cloud = { cloud.createCustomer(customer) },
        )
        return true
    }

    suspend fun delete(id: String): Boolean {
        if (!access.actor().can(Permission.MANAGE_CUSTOMERS)) return false
        val now = System.currentTimeMillis()
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { customerDao.softDelete(id, now) },
            cloud = { cloud.deleteCustomer(id, now) },
        )
        return true
    }
}
