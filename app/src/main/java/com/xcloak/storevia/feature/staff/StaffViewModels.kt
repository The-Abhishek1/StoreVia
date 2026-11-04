package com.xcloak.storevia.feature.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.dao.AuditDao
import com.xcloak.storevia.core.database.dao.StaffDao
import com.xcloak.storevia.core.database.entity.AuditEntity
import com.xcloak.storevia.core.database.entity.StaffEntity
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.AppSettings
import com.xcloak.storevia.data.staff.AuthResult
import com.xcloak.storevia.data.staff.StaffException
import com.xcloak.storevia.data.staff.StaffRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val session: StaffSession,
    private val prefs: AppPrefs,
    private val audit: AuditSink,
    dao: StaffDao,
) : ViewModel() {
    val staff: StateFlow<List<StaffEntity>?> = dao.observeAll().map<List<StaffEntity>, List<StaffEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val staffMode: StateFlow<Boolean> = session.staffMode
    val actor: StateFlow<Actor> = session.actorState
    val settings: StateFlow<AppSettings> = prefs.settings

    /** Runs [block]; returns a string-resource id for the problem, or null on success. */
    private suspend fun attempt(block: suspend () -> Unit): Int? = try { block(); null }
        catch (e: StaffException) { e.error.res() }
        catch (e: PermissionDeniedException) { R.string.err_permission_denied }

    private fun mismatch(a: String, b: String) = if (a != b) R.string.err_pin_mismatch else null

    /** Turns staff accounts on: the current holder becomes a PIN-protected owner and stays signed in. */
    suspend fun enable(name: String, pin: String, again: String): Int? = mismatch(pin, again) ?: attempt { session.login(repo.createOwner(name, pin)) }

    suspend fun add(name: String, role: Role, pin: String, again: String): Int? = mismatch(pin, again) ?: attempt {
        session.actor().let { if (!it.can(Permission.MANAGE_STAFF)) throw PermissionDeniedException(Permission.MANAGE_STAFF) }
        repo.addStaff(name, role, pin)
    }

    suspend fun update(id: String, name: String, role: Role, active: Boolean): Int? = attempt {
        if (!session.actor().can(Permission.MANAGE_STAFF)) throw PermissionDeniedException(Permission.MANAGE_STAFF)
        repo.update(id, name, role, active)
    }

    suspend fun resetPin(id: String, pin: String, again: String): Int? = mismatch(pin, again) ?: attempt {
        if (!session.actor().can(Permission.MANAGE_STAFF)) throw PermissionDeniedException(Permission.MANAGE_STAFF)
        repo.setPin(id, pin)
    }

    fun setCashierDiscount(on: Boolean) = change("Cashiers can give discounts: ${if (on) "on" else "off"}") { prefs.update { copy(cashierCanDiscount = on) } }
    fun setCashierCredit(on: Boolean) = change("Cashiers can sell on credit: ${if (on) "on" else "off"}") { prefs.update { copy(cashierCanSellOnCredit = on) } }
    fun setCashierBatchOverride(on: Boolean) = change("Cashiers can override FEFO batch selection: ${if (on) "on" else "off"}") {
        prefs.update { copy(cashierCanOverrideBatch = on) }
    }

    private fun change(summary: String, apply: () -> Unit) {
        if (!session.actor().can(Permission.MANAGE_STAFF)) return
        apply()
        viewModelScope.launch { audit.log(AuditAction.SETTINGS_CHANGED, summary) }
    }

    fun switchUser() = session.logout()
}

@HiltViewModel
class StaffLoginViewModel @Inject constructor(private val repo: StaffRepository, private val session: StaffSession) : ViewModel() {
    val staff: StateFlow<List<StaffEntity>> = session.staff.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun signIn(id: String, pin: String): AuthResult {
        val r = repo.authenticate(id, pin)
        if (r is AuthResult.Success) session.login(r.staff.id)
        return r
    }
}

@HiltViewModel
class AuditViewModel @Inject constructor(dao: AuditDao) : ViewModel() {
    val rows: StateFlow<List<AuditEntity>?> = dao.observeRecent().map<List<AuditEntity>, List<AuditEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Small helper so the More menu can show who is signed in and offer "Switch user". */
@HiltViewModel
class AccessViewModel @Inject constructor(private val session: StaffSession) : ViewModel() {
    val staffMode: StateFlow<Boolean> = session.staffMode
    val actor: StateFlow<Actor> = session.actorState
    fun switchUser() = session.logout()
}
