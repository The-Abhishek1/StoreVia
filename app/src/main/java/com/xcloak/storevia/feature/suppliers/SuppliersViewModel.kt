package com.xcloak.storevia.feature.suppliers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.access.Access
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.database.dao.SupplierBalanceRow
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.util.GstinValidator
import com.xcloak.storevia.data.cloud.CloudPartyRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageModeRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** Blank GSTIN is fine; otherwise the format and check character must be right (the state is taken from its first two digits). */
internal fun gstinOk(gstin: String): Boolean =
    gstin.isBlank() || GstinValidator.normalize(gstin).let { GstinValidator.validate(it, it.take(2)) == GstinValidator.Result.VALID }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SuppliersViewModel @Inject constructor(
    private val dao: SupplierDao,
    private val access: Access,
    private val cloud: CloudPartyRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val rows: StateFlow<List<SupplierBalanceRow>?> = combine(_query, prefs.settings) { query, settings ->
        query.trim() to settings.storageMode
    }.flatMapLatest { (query, mode) ->
        StorageModeRouter.route(mode, local = { dao.observeWithBalance(query) }, cloud = { cloud.observeSuppliers(query) })
            .map<List<SupplierBalanceRow>, List<SupplierBalanceRow>?> { it }
            .onStart { emit(null) }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totalPayable: StateFlow<Long> = StorageModeRouter.observe(
        prefs.settings.map { it.storageMode },
        local = { dao.observeTotalPayable() },
        cloud = { cloud.observeTotalSupplierPayable() },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun setQuery(q: String) { _query.value = q }

    /** @return false if the GSTIN is invalid or the name is blank. */
    suspend fun add(name: String, phone: String, gstin: String): Boolean {
        if (name.isBlank() || !gstinOk(gstin) || !access.actor().can(Permission.MANAGE_PURCHASES)) return false
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return false
        val supplier = SupplierEntity(name = name.trim(), phone = phone.trim().ifEmpty { null }, gstin = gstin.trim().uppercase().ifEmpty { null })
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { dao.insert(supplier) },
            cloud = { cloud.createSupplier(supplier) },
        )
        return true
    }

    suspend fun delete(id: String): Boolean {
        if (!access.actor().can(Permission.MANAGE_PURCHASES)) return false
        val now = System.currentTimeMillis()
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { dao.softDelete(id, now) },
            cloud = { cloud.deleteSupplier(id, now) },
        )
        return true
    }
}
