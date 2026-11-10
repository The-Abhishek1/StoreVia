package com.xcloak.storevia.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Access
import com.xcloak.storevia.core.access.AuditAction
import com.xcloak.storevia.core.access.AuditSink
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.entity.BranchEntity
import com.xcloak.storevia.data.cloud.CloudSettingsRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BranchSettingsUi(
    val branches: List<BranchEntity> = emptyList(),
    val selectedId: String? = null,
    val name: String = "",
    @StringRes val error: Int? = null,
    val cloudMode: Boolean = false,
    val loading: Boolean = true,
    @StringRes val loadError: Int? = null,
)

@HiltViewModel
class BranchSettingsViewModel @Inject constructor(
    private val branchDao: BranchDao,
    private val access: Access,
    private val audit: AuditSink,
    private val prefs: AppPrefs,
    private val cloudSettings: CloudSettingsRepository,
) : ViewModel() {
    private val input = MutableStateFlow(BranchSettingsUi())
    val ui: StateFlow<BranchSettingsUi> = input.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.settings.map { it.storageMode }.distinctUntilChanged().collectLatest { mode ->
                input.update { it.copy(cloudMode = mode == StorageMode.CLOUD, loading = true, loadError = null) }
                if (mode == StorageMode.CLOUD) {
                    refreshCloudBranches()
                } else {
                    combine(branchDao.observeAll(), branchDao.observeSelectedId()) { branches, selectedId ->
                        input.update {
                            it.copy(
                                branches = branches,
                                selectedId = selectedId,
                                loading = false,
                                loadError = null,
                            )
                        }
                    }.collect { }
                }
            }
        }
    }

    fun refresh() {
        if (input.value.cloudMode) viewModelScope.launch { refreshCloudBranches() }
    }

    private suspend fun refreshCloudBranches() {
        input.update { it.copy(loading = true, loadError = null) }
        try {
            val branches = cloudSettings.loadBranches()
            val preferred = cloudSettings.selectedBranchId()
            val selected = preferred?.takeIf { id -> branches.any { it.id == id } }
                ?: branches.firstOrNull()?.id
            cloudSettings.setSelectedBranchId(selected)
            input.update {
                it.copy(branches = branches, selectedId = selected, loading = false, loadError = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            input.update { it.copy(branches = emptyList(), loading = false, loadError = R.string.cloud_settings_load_failed) }
        }
    }

    fun setName(name: String) = input.update { it.copy(name = name, error = null) }

    fun select(id: String) = viewModelScope.launch {
        if (!access.actor().can(Permission.MANAGE_SETTINGS)) {
            input.update { it.copy(error = R.string.err_permission_denied) }
            return@launch
        }
        if (input.value.cloudMode) {
            if (input.value.branches.none { it.id == id }) {
                input.update { it.copy(error = R.string.branch_not_found) }
                return@launch
            }
            cloudSettings.setSelectedBranchId(id)
            // Only the device-local selection flag is mirrored to Room for existing branch consumers.
            if (branchDao.getById(id) != null) branchDao.selectOnDevice(id, System.currentTimeMillis())
            input.update { it.copy(selectedId = id, error = null) }
        } else if (branchDao.selectOnDevice(id, System.currentTimeMillis()) == 0) {
            input.update { it.copy(error = R.string.branch_not_found) }
        } else {
            input.update { it.copy(error = null) }
        }
    }

    fun add() = viewModelScope.launch {
        if (!access.actor().can(Permission.MANAGE_SETTINGS)) {
            input.update { it.copy(error = R.string.err_permission_denied) }
            return@launch
        }
        val name = input.value.name.trim()
        if (name.isBlank()) {
            input.update { it.copy(error = R.string.branch_name_required) }
            return@launch
        }
        if (input.value.cloudMode) {
            if (input.value.branches.any { it.name.equals(name, ignoreCase = true) }) {
                input.update { it.copy(error = R.string.branch_name_duplicate) }
                return@launch
            }
            try {
                val branch = cloudSettings.addBranch(name)
                cloudSettings.setSelectedBranchId(branch.id)
                input.update {
                    it.copy(
                        branches = (it.branches + branch).sortedBy { row -> row.name.lowercase() },
                        selectedId = branch.id,
                        name = "",
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                input.update {
                    it.copy(error = if (e.message?.contains("already exists", true) == true) {
                        R.string.branch_name_duplicate
                    } else R.string.cloud_settings_save_failed)
                }
            }
            return@launch
        }
        if (branchDao.nameExists(name, "")) {
            input.update { it.copy(error = R.string.branch_name_duplicate) }
            return@launch
        }
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        branchDao.insert(BranchEntity(id = id, name = name, selectedOnDevice = true, createdAt = now, updatedAt = now))
        branchDao.selectOnDevice(id, now)
        input.update { it.copy(name = "", error = null) }
        audit.log(AuditAction.SETTINGS_CHANGED, "Branch added: $name")
    }

    suspend fun rename(id: String, rawName: String): Int? {
        if (!access.actor().can(Permission.MANAGE_SETTINGS)) return R.string.err_permission_denied
        val name = rawName.trim()
        if (name.isBlank()) return R.string.branch_name_required

        if (input.value.cloudMode) {
            if (input.value.branches.any { it.id != id && it.name.equals(name, ignoreCase = true) }) {
                return R.string.branch_name_duplicate
            }
            return try {
                val renamed = cloudSettings.renameBranch(id, name)
                input.update {
                    it.copy(branches = it.branches.map { branch -> if (branch.id == id) renamed else branch })
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.contains("already exists", true) == true) R.string.branch_name_duplicate
                else R.string.cloud_settings_save_failed
            }
        }

        if (branchDao.nameExists(name, id)) return R.string.branch_name_duplicate
        val branch = branchDao.getById(id) ?: return R.string.branch_not_found
        branchDao.update(branch.copy(name = name, updatedAt = System.currentTimeMillis(), dirty = true))
        audit.log(AuditAction.SETTINGS_CHANGED, "Branch renamed: ${branch.name} -> $name")
        return null
    }
}
