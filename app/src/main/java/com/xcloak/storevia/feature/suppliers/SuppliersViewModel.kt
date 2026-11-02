package com.xcloak.storevia.feature.suppliers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.database.dao.SupplierBalanceRow
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.util.GstinValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Blank GSTIN is fine; otherwise the format and check character must be right (the state is taken from its first two digits). */
internal fun gstinOk(gstin: String): Boolean =
    gstin.isBlank() || GstinValidator.normalize(gstin).let { GstinValidator.validate(it, it.take(2)) == GstinValidator.Result.VALID }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SuppliersViewModel @Inject constructor(private val dao: SupplierDao) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val rows: StateFlow<List<SupplierBalanceRow>?> = _query.flatMapLatest { dao.observeWithBalance(it.trim()) }
        .map<List<SupplierBalanceRow>, List<SupplierBalanceRow>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalPayable: StateFlow<Long> = dao.observeTotalPayable().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setQuery(q: String) { _query.value = q }

    /** @return false if the GSTIN is invalid or the name is blank. */
    fun add(name: String, phone: String, gstin: String): Boolean {
        if (name.isBlank() || !gstinOk(gstin)) return false
        viewModelScope.launch {
            dao.insert(SupplierEntity(name = name.trim(), phone = phone.trim().ifEmpty { null }, gstin = gstin.trim().uppercase().ifEmpty { null }))
        }
        return true
    }
}
