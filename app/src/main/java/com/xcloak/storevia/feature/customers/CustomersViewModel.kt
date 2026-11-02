package com.xcloak.storevia.feature.customers

import com.xcloak.storevia.core.access.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.CustomerBalanceRow
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.util.Parsing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CustomersViewModel @Inject constructor(private val customerDao: CustomerDao, private val access: Access) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val rows: StateFlow<List<CustomerBalanceRow>?> = _query
        .flatMapLatest { customerDao.observeWithBalance(it.trim()) }
        .map<List<CustomerBalanceRow>, List<CustomerBalanceRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalDue: StateFlow<Long> = customerDao.observeTotalDue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setQuery(q: String) { _query.value = q }

    /** @return false if the credit limit text is invalid. */
    fun add(name: String, phone: String, limitText: String): Boolean {
        if (name.isBlank()) return false
        val limit = if (limitText.isBlank()) null else (Parsing.moneyToPaise(limitText) ?: return false)
        if (limit != null && !access.actor().can(Permission.EDIT_CREDIT_LIMIT)) return false
        viewModelScope.launch {
            customerDao.insert(CustomerEntity(name = name.trim(), phone = phone.trim().ifEmpty { null }, creditLimitPaise = limit))
        }
        return true
    }
}
