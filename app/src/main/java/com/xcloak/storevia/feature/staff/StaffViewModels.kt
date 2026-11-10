package com.xcloak.storevia.feature.staff

import android.util.Log
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.dao.AuditDao
import com.xcloak.storevia.core.database.dao.StaffDao
import com.xcloak.storevia.core.database.entity.AuditEntity
import com.xcloak.storevia.core.database.entity.StaffEntity
import com.xcloak.storevia.data.cloud.CloudSettingsRepository
import com.xcloak.storevia.data.cloud.CloudStaffInvite
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.AppSettings
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.staff.AuthResult
import com.xcloak.storevia.data.staff.StaffException
import com.xcloak.storevia.data.staff.StaffRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STAFF_VIEW_MODEL_TAG = "StaffViewModel"

private fun StaffException.Error.res() = when (this) {
    StaffException.Error.NAME_REQUIRED -> R.string.err_name_required
    StaffException.Error.NAME_TAKEN -> R.string.err_staff_name_taken
    StaffException.Error.PIN_BAD_LENGTH -> R.string.err_pin_length
    StaffException.Error.PIN_NOT_DIGITS -> R.string.err_pin_digits
    StaffException.Error.PIN_TOO_SIMPLE -> R.string.err_pin_simple
    StaffException.Error.OWNER_EXISTS -> R.string.err_owner_exists
    StaffException.Error.NOT_FOUND -> R.string.err_generic
    StaffException.Error.LAST_OWNER -> R.string.err_last_owner
}

@HiltViewModel
class StaffViewModel @Inject constructor(
    private val repo: StaffRepository,
    private val cloudSettings: CloudSettingsRepository,
    private val session: StaffSession,
    private val prefs: AppPrefs,
    private val audit: AuditSink,
    dao: StaffDao,
) : ViewModel() {
    private val _staff = MutableStateFlow<List<StaffEntity>?>(null)
    val staff: StateFlow<List<StaffEntity>?> = _staff.asStateFlow()
    val staffMode: StateFlow<Boolean> = session.staffMode
    val actor: StateFlow<Actor> = session.actorState
    val settings: StateFlow<AppSettings> = prefs.settings
    val cloudMode: StateFlow<Boolean> = prefs.settings.map { it.storageMode == StorageMode.CLOUD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.settings.value.storageMode == StorageMode.CLOUD)
    private val _loadError = MutableStateFlow<Int?>(null)
    val loadError: StateFlow<Int?> = _loadError.asStateFlow()
    private val _staffInvites = MutableStateFlow<List<CloudStaffInvite>>(emptyList())
    val staffInvites: StateFlow<List<CloudStaffInvite>> = _staffInvites.asStateFlow()
    private val _inviteLoadError = MutableStateFlow<Int?>(null)
    val inviteLoadError: StateFlow<Int?> = _inviteLoadError.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.settings.map { it.storageMode }.distinctUntilChanged().collectLatest { mode ->
                _staff.value = null
                _loadError.value = null
                _staffInvites.value = emptyList()
                _inviteLoadError.value = null
                if (mode == StorageMode.CLOUD) {
                    refreshCloudStaff()
                } else {
                    dao.observeAll().collect { _staff.value = it }
                }
            }
        }
        viewModelScope.launch {
            session.actorState.map { it.role }.distinctUntilChanged().drop(1).collect {
                if (cloudMode.value) refreshCloudInvites()
            }
        }
    }

    fun refresh() {
        if (cloudMode.value) viewModelScope.launch { refreshCloudStaff() }
    }

    private suspend fun refreshCloudStaff() {
        _staff.value = null
        _loadError.value = null
        _inviteLoadError.value = null
        try {
            _staff.value = cloudSettings.loadMembers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(STAFF_VIEW_MODEL_TAG, "Could not load cloud staff memberships", e)
            _staff.value = emptyList()
            _loadError.value = R.string.cloud_staff_load_failed
        }
        refreshCloudInvites()
    }

    private suspend fun refreshCloudInvites() {
        _inviteLoadError.value = null
        if (session.actor().role == Role.OWNER) {
            try {
                _staffInvites.value = cloudSettings.loadStaffInvites()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(STAFF_VIEW_MODEL_TAG, "Could not load pending cloud staff invitations", e)
                _staffInvites.value = emptyList()
                _inviteLoadError.value = R.string.cloud_staff_invites_load_failed
            }
        } else {
            _staffInvites.value = emptyList()
        }
    }

    suspend fun inviteCloudStaff(email: String, role: Role): Int? {
        if (prefs.settings.value.storageMode != StorageMode.CLOUD) return R.string.cloud_staff_invite_failed
        if (session.actor().role != Role.OWNER) return R.string.err_permission_denied
        val normalizedEmail = email.trim()
        if (!Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) return R.string.cloud_staff_email_invalid
        return try {
            cloudSettings.inviteStaffMember(normalizedEmail, role)
            refreshCloudStaff()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(STAFF_VIEW_MODEL_TAG, "Could not create cloud staff invitation", e)
            R.string.cloud_staff_invite_failed
        }
    }

    /** Runs [block]; returns a string-resource id for the problem, or null on success. */
    private suspend fun attempt(block: suspend () -> Unit): Int? = try { block(); null }
        catch (e: StaffException) { e.error.res() }
        catch (e: PermissionDeniedException) { R.string.err_permission_denied }

    private fun mismatch(a: String, b: String) = if (a != b) R.string.err_pin_mismatch else null

    /** Turns staff accounts on: the current holder becomes a PIN-protected owner and stays signed in. */
    suspend fun enable(name: String, pin: String, again: String): Int? =
        if (prefs.settings.value.storageMode == StorageMode.CLOUD) R.string.cloud_staff_pin_unsupported
        else mismatch(pin, again) ?: attempt { session.login(repo.createOwner(name, pin)) }

    suspend fun add(name: String, role: Role, pin: String, again: String): Int? = if (prefs.settings.value.storageMode == StorageMode.CLOUD) {
        R.string.cloud_staff_pin_unsupported
    } else mismatch(pin, again) ?: attempt {
        session.actor().let { if (!it.can(Permission.MANAGE_STAFF)) throw PermissionDeniedException(Permission.MANAGE_STAFF) }
        repo.addStaff(name, role, pin)
    }

    suspend fun update(id: String, name: String, role: Role, active: Boolean): Int? = if (prefs.settings.value.storageMode == StorageMode.CLOUD) {
        R.string.cloud_staff_pin_unsupported
    } else attempt {
        if (!session.actor().can(Permission.MANAGE_STAFF)) throw PermissionDeniedException(Permission.MANAGE_STAFF)
        repo.update(id, name, role, active)
    }

    suspend fun resetPin(id: String, pin: String, again: String): Int? = if (prefs.settings.value.storageMode == StorageMode.CLOUD) {
        R.string.cloud_staff_pin_unsupported
    } else mismatch(pin, again) ?: attempt {
        if (!session.actor().can(Permission.MANAGE_STAFF)) throw PermissionDeniedException(Permission.MANAGE_STAFF)
        repo.setPin(id, pin)
    }

    fun setCashierDiscount(on: Boolean) = change("Cashiers can give discounts: ${if (on) "on" else "off"}") { prefs.update { copy(cashierCanDiscount = on) } }
    fun setCashierCredit(on: Boolean) = change("Cashiers can sell on credit: ${if (on) "on" else "off"}") { prefs.update { copy(cashierCanSellOnCredit = on) } }
    fun setCashierBatchOverride(on: Boolean) = change("Cashiers can override FEFO batch selection: ${if (on) "on" else "off"}") {
        prefs.update { copy(cashierCanOverrideBatch = on) }
    }

    private fun change(summary: String, apply: () -> Unit) {
        if (prefs.settings.value.storageMode == StorageMode.CLOUD) return
        if (!session.actor().can(Permission.MANAGE_STAFF)) return
        apply()
        viewModelScope.launch { audit.log(AuditAction.SETTINGS_CHANGED, summary) }
    }

    fun switchUser() {
        if (prefs.settings.value.storageMode != StorageMode.CLOUD) session.logout()
    }
}

@HiltViewModel
class StaffLoginViewModel @Inject constructor(
    private val repo: StaffRepository,
    private val session: StaffSession,
    private val prefs: AppPrefs,
) : ViewModel() {
    val staff: StateFlow<List<StaffEntity>> = session.staff.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun signIn(id: String, pin: String): AuthResult {
        if (prefs.settings.value.storageMode == StorageMode.CLOUD) return AuthResult.Unknown
        val r = repo.authenticate(id, pin)
        if (r is AuthResult.Success) session.login(r.staff.id)
        return r
    }
}

@HiltViewModel
class AuditViewModel @Inject constructor(
    dao: AuditDao,
    session: StaffSession,
    prefs: AppPrefs,
    private val cloudSettings: CloudSettingsRepository,
) : ViewModel() {
    val canView: StateFlow<Boolean> = session.actorState
        .map { it.can(Permission.VIEW_AUDIT) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.actor().can(Permission.VIEW_AUDIT))

    private val _rows = MutableStateFlow<List<AuditEntity>?>(null)
    val rows: StateFlow<List<AuditEntity>?> = _rows.asStateFlow()
    private val _loadError = MutableStateFlow<Int?>(null)
    val loadError: StateFlow<Int?> = _loadError.asStateFlow()

    init {
        viewModelScope.launch {
            combine(prefs.settings.map { it.storageMode }.distinctUntilChanged(), canView) { mode, allowed -> mode to allowed }
                .distinctUntilChanged()
                .collectLatest { (mode, allowed) ->
                    _rows.value = null
                    _loadError.value = null
                    if (!allowed) {
                        _rows.value = emptyList()
                    } else if (mode == StorageMode.CLOUD) {
                        try {
                            _rows.value = cloudSettings.loadAuditLog()
                        } catch (_: Exception) {
                            _rows.value = emptyList()
                            _loadError.value = R.string.cloud_audit_load_failed
                        }
                    } else {
                        dao.observeRecent().collect { _rows.value = it }
                    }
                }
        }
    }
}

/** Small helper so the More menu can show who is signed in and offer "Switch user". */
@HiltViewModel
class AccessViewModel @Inject constructor(private val session: StaffSession) : ViewModel() {
    val staffMode: StateFlow<Boolean> = session.staffMode
    val actor: StateFlow<Actor> = session.actorState
    fun switchUser() = session.logout()
}
