package com.xcloak.storevia.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.core.util.Parsing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlatformAdminUiState(
    val overview: AdminOverview? = null,
    val busy: Boolean = false,
    val accessDenied: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class PlatformAdminViewModel @Inject constructor(
    private val repository: PlatformAdminRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(PlatformAdminUiState(busy = true))
    val ui: StateFlow<PlatformAdminUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() = launchAction { loadOverview() }

    private suspend fun loadOverview() {
        if (!repository.hasAccess()) {
            _ui.value = PlatformAdminUiState(accessDenied = true, message = "This account is not authorized for platform administration.")
        } else {
            val overview = repository.overview()
            _ui.update { it.copy(overview = overview, accessDenied = false) }
        }
    }

    fun createStore(name: String, ownerEmail: String, storeType: String) = launchAction {
        repository.createStore(name.trim(), ownerEmail.trim(), storeType)
        _ui.update { it.copy(message = "Store created and owner invited.") }
        loadOverview()
    }

    fun updateStore(store: AdminStore, name: String, address: String, phone: String, gstin: String, storeType: String) = launchAction {
        repository.updateStore(store, name.trim(), address.trim(), phone.trim(), gstin.trim(), storeType)
        _ui.update { it.copy(message = "Store details updated.") }
        loadOverview()
    }

    fun hold(storeId: String) = changeStatus(storeId, "hold_store")
    fun restore(storeId: String) = changeStatus(storeId, "restore_store")
    fun delete(storeId: String) = changeStatus(storeId, "delete_store")

    fun recordPayment(storeId: String, amount: String, note: String) = launchAction {
        val amountMinor = Parsing.moneyToPaise(amount) ?: error("Enter a valid payment amount.")
        require(amountMinor > 0) { "Payment amount must be greater than zero." }
        repository.recordPayment(storeId, amountMinor, note.trim().also { require(it.isNotEmpty()) { "Add a payment note." } })
        _ui.update { it.copy(message = "Payment record saved.") }
        loadOverview()
    }

    private fun changeStatus(storeId: String, action: String) = launchAction {
        repository.setStatus(storeId, action)
        _ui.update { it.copy(message = "Store status updated.") }
        loadOverview()
    }

    private fun launchAction(block: suspend () -> Unit) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        try {
            block()
        } catch (error: Exception) {
            _ui.update { it.copy(busy = false, message = error.message ?: "Platform admin request failed.") }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }
}