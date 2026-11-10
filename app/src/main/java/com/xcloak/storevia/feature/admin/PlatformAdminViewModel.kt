package com.xcloak.storevia.feature.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class PlatformAdminUiState(
    val overview: AdminOverview? = null,
    val busy: Boolean = false,
    val accessDenied: Boolean = false,
    val serviceUpdateRequired: Boolean = false,
    val message: String? = null,
    val deletionPreview: AdminDeletionPreview? = null,
    val deletionConfirmation: String = "",
)

@HiltViewModel
class PlatformAdminViewModel @Inject constructor(
    private val repository: PlatformAdminRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(PlatformAdminUiState(busy = true))
    val ui: StateFlow<PlatformAdminUiState> = _ui.asStateFlow()
    private val actionInProgress = AtomicBoolean(false)

    init { refresh() }

    fun refresh() = launchAction { loadOverview() }

    private suspend fun loadOverview(): AdminOverview? {
        if (!repository.hasAccess()) {
            _ui.value = PlatformAdminUiState(accessDenied = true, message = "This account is not authorized for platform administration.")
            return null
        } else {
            return try {
                val overview = repository.overview()
                _ui.update {
                    it.copy(
                        overview = overview,
                        accessDenied = false,
                        serviceUpdateRequired = false,
                        message = null,
                    )
                }
                overview
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    fun setPlan(storeId: String, plan: String) = launchAction {
        repository.setPlan(storeId, plan)
        val refreshed = loadOverview()
        val storedPlan = refreshed?.accounts
            ?.asSequence()
            ?.flatMap { it.subscriptions.asSequence() }
            ?.firstOrNull { it.storeId == storeId }
        check(storedPlan != null && storedPlan.plan.equals(plan, ignoreCase = true) && storedPlan.isActiveAt()) {
            "The server accepted the request but the refreshed store subscription does not match. Refresh and retry."
        }
        _ui.update { it.copy(message = if (plan == "pro") "Pro access enabled for testing." else "Pro access disabled.") }
    }

    fun previewDeletion(kind: String, targetId: String) = launchAction {
        val preview = repository.deletionPreview(kind, targetId)
        _ui.update { it.copy(deletionPreview = preview, deletionConfirmation = "") }
    }

    fun updateDeletionConfirmation(value: String) =
        _ui.update { it.copy(deletionConfirmation = value) }

    fun dismissDeletion() =
        _ui.update { it.copy(deletionPreview = null, deletionConfirmation = "") }

    fun confirmDeletion() {
        val preview = _ui.value.deletionPreview ?: return
        val confirmation = _ui.value.deletionConfirmation
        if (confirmation != preview.confirmationText) return
        launchAction {
            val result = repository.deleteScope(preview, confirmation)
            dismissDeletion()
            _ui.update {
                it.copy(
                    message = if (result.failedUsers.isEmpty()) {
                        "Deleted ${result.deletedUserCount} account(s), ${result.deletedStoreCount} store(s), and ${result.deletedRowCount} associated record(s)."
                    } else {
                        "Store data was deleted, but ${result.failedUsers.size} cloud account(s) could not be removed. Review the remaining unlinked accounts and retry."
                    },
                )
            }
            loadOverview()
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (!actionInProgress.compareAndSet(false, true)) return
        _ui.update {
            it.copy(
                busy = true,
                message = null,
                serviceUpdateRequired = false,
                accessDenied = false,
            )
        }
        viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is PlatformAdminServiceUpdateRequiredException) {
                    _ui.update { it.copy(serviceUpdateRequired = true, message = null) }
                } else {
                    _ui.update { it.copy(message = error.message ?: "Platform admin request failed.") }
                }
            } finally {
                actionInProgress.set(false)
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}