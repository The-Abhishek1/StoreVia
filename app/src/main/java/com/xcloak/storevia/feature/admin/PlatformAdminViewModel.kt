package com.xcloak.storevia.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    fun setPlan(storeId: String, plan: String) = launchAction {
        repository.setPlan(storeId, plan)
        _ui.update { it.copy(message = if (plan == "pro") "Pro access enabled for testing." else "Pro access disabled.") }
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