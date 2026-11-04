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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BranchSettingsUi(
    val branches: List<BranchEntity> = emptyList(),
    val selectedId: String? = null,
    val name: String = "",
    @StringRes val error: Int? = null,
)

@HiltViewModel
class BranchSettingsViewModel @Inject constructor(
    private val branchDao: BranchDao,
    private val access: Access,
    private val audit: AuditSink,
) : ViewModel() {
    private val input = MutableStateFlow(BranchSettingsUi())
    val ui: StateFlow<BranchSettingsUi> = combine(
        input,
        branchDao.observeAll(),
        branchDao.observeSelectedId(),
    ) { state, branches, selectedId ->
        state.copy(branches = branches, selectedId = selectedId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BranchSettingsUi())

    fun setName(name: String) = input.update { it.copy(name = name, error = null) }

    fun select(id: String) = viewModelScope.launch {
        if (!access.actor().can(Permission.MANAGE_SETTINGS)) {
            input.update { it.copy(error = R.string.err_permission_denied) }
            return@launch
        }
        if (branchDao.selectOnDevice(id, System.currentTimeMillis()) == 0) {
            input.update { it.copy(error = R.string.branch_not_found) }
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
        val id = java.util.UUID.randomUUID().toString()
        if (branchDao.nameExists(name, id)) {
            input.update { it.copy(error = R.string.branch_name_duplicate) }
            return@launch
        }
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
        if (branchDao.nameExists(name, id)) return R.string.branch_name_duplicate
        val branch = branchDao.getById(id) ?: return R.string.branch_not_found
        branchDao.update(branch.copy(name = name, updatedAt = System.currentTimeMillis(), dirty = true))
        audit.log(AuditAction.SETTINGS_CHANGED, "Branch renamed: ${branch.name} -> $name")
        return null
    }
}
