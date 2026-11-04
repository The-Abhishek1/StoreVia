package com.xcloak.storevia.feature.suppliers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.access.Access
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.database.dao.SupplierBalanceRow
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.util.GstinValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** Blank GSTIN is fine; otherwise the format and check character must be right (the state is taken from its first two digits). */
internal fun gstinOk(gstin: String): Boolean =
    gstin.isBlank() || GstinValidator.normalize(gstin).let { GstinValidator.validate(it, it.take(2)) == GstinValidator.Result.VALID }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SuppliersViewModel @Inject constructor(private val dao: SupplierDao, private val access: Access) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val rows: StateFlow<List<SupplierBalanceRow>?> = _query.flatMapLatest { query ->
        dao.observeWithBalance(query.trim())
            .map<List<SupplierBalanceRow>, List<SupplierBalanceRow>?> { it }
            .onStart { emit(null) }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalPayable: StateFlow<Long> = dao.observeTotalPayable().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setQuery(q: String) { _query.value = q }

    /** @return false if the GSTIN is invalid or the name is blank. */
    suspend fun add(name: String, phone: String, gstin: String): Boolean {
        if (name.isBlank() || !gstinOk(gstin) || !access.actor().can(Permission.MANAGE_PURCHASES)) return false
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return false
        dao.insert(SupplierEntity(name = name.trim(), phone = phone.trim().ifEmpty { null }, gstin = gstin.trim().uppercase().ifEmpty { null }))
        return true
    }
}
