package com.xcloak.storevia.core.access

import com.xcloak.storevia.core.database.dao.StaffDao
import com.xcloak.storevia.core.database.entity.StaffEntity
import com.xcloak.storevia.core.security.AppLockLogic
import com.xcloak.storevia.data.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is using the phone right now.
 * - No staff accounts yet: the holder of the phone is the implicit owner (nothing changes for a one-person shop).
 * - Staff accounts exist: nobody can do anything until someone signs in with their PIN; the session ends after the
 *   app has been in the background for [IDLE_MS], or on "Switch user".
 */
@Singleton
class StaffSession @Inject constructor(staffDao: StaffDao, private val prefs: AppPrefs) : Access {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** null while the first database read is still running. */
    val staff: StateFlow<List<StaffEntity>?> = staffDao.observeActive().map<List<StaffEntity>, List<StaffEntity>?> { it }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val currentId = MutableStateFlow<String?>(null)
    private var stoppedAt = 0L

    val staffMode: StateFlow<Boolean> = staff.map { !it.isNullOrEmpty() }.stateIn(scope, SharingStarted.Eagerly, false)

    val needsLogin: StateFlow<Boolean> = combine(staff, currentId) { list, id -> !list.isNullOrEmpty() && list.none { it.id == id } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val actorState: StateFlow<Actor> = combine(staff, currentId, prefs.settings) { list, id, settings ->
        val options = CashierOptions(settings.cashierCanDiscount, settings.cashierCanSellOnCredit)
        when {
            list == null -> Actor.NONE
            list.isEmpty() -> Actor.LOCAL_OWNER
            else -> list.firstOrNull { it.id == id }?.let { Actor(it.id, it.name, it.role, RolePermissions.of(it.role, options)) } ?: Actor.NONE
        }
    }.stateIn(scope, SharingStarted.Eagerly, Actor.NONE)

    override fun actor(): Actor = actorState.value

    fun login(staffId: String) { currentId.value = staffId; stoppedAt = 0L }
    fun logout() { currentId.value = null }

    fun onStop(now: Long = System.currentTimeMillis()) { stoppedAt = now }
    fun onStart(now: Long = System.currentTimeMillis()) {
        if (AppLockLogic.shouldLock(staffMode.value, currentId.value == null, stoppedAt, now, IDLE_MS)) logout()
    }

    companion object { const val IDLE_MS = 5 * 60_000L }
}
