package com.xcloak.storevia.core.access

import com.xcloak.storevia.core.database.dao.StaffDao
import com.xcloak.storevia.core.database.entity.StaffEntity
import com.xcloak.storevia.core.security.AppLockLogic
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
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
class StaffSession @Inject constructor(
    staffDao: StaffDao,
    private val prefs: AppPrefs,
    private val supabase: SupabaseClient,
) : Access {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** null while the first database read is still running. */
    val staff: StateFlow<List<StaffEntity>?> = staffDao.observeActive().map<List<StaffEntity>, List<StaffEntity>?> { it }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val currentId = MutableStateFlow<String?>(null)
    private var stoppedAt = 0L

    private val cloudAuthenticated: StateFlow<Boolean> = supabase.auth.sessionStatus
        .map { status -> status to (supabase.auth.currentSessionOrNull() != null) }
        .scan(supabase.auth.currentSessionOrNull() != null) { previouslyAuthenticated, (status, hasSession) ->
            resolveCloudAuthentication(
                isAuthenticated = status is SessionStatus.Authenticated,
                isInitializing = status is SessionStatus.Initializing,
                hasSession = hasSession,
                previouslyAuthenticated = previouslyAuthenticated,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, supabase.auth.currentSessionOrNull() != null)

    /** A Supabase account is the cloud identity; local PIN staff mode is never enabled in Cloud mode. */
    val staffMode: StateFlow<Boolean> = combine(staff, prefs.settings) { list, settings ->
        settings.storageMode != StorageMode.CLOUD && !list.isNullOrEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val needsLogin: StateFlow<Boolean> = combine(staff, currentId, prefs.settings) { list, id, settings ->
        settings.storageMode != StorageMode.CLOUD && !list.isNullOrEmpty() && list.none { it.id == id }
    }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val actorState: StateFlow<Actor> = combine(staff, currentId, prefs.settings, cloudAuthenticated) { list, id, settings, authenticated ->
        when {
            settings.storageMode == StorageMode.CLOUD ->
                cloudActor(settings.cloudRole, authenticated)
            list == null -> Actor.NONE
            list.isEmpty() -> Actor.LOCAL_OWNER
            else -> list.firstOrNull { it.id == id }?.let {
                Actor(it.id, it.name, it.role, RolePermissions.of(it.role, localCashierOptions(settings)))
            } ?: Actor.NONE
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

internal fun resolveCloudAuthentication(
    isAuthenticated: Boolean,
    isInitializing: Boolean,
    hasSession: Boolean,
    previouslyAuthenticated: Boolean,
): Boolean = isAuthenticated || (isInitializing && (hasSession || previouslyAuthenticated))

internal fun cloudActor(roleName: String?, authenticated: Boolean): Actor {
    val role = when (roleName?.uppercase()) {
        "OWNER" -> Role.OWNER
        "MANAGER" -> Role.MANAGER
        "CASHIER" -> Role.CASHIER
        else -> null
    }
    if (!authenticated || role == null) return Actor.NONE
    return Actor(
        id = "supabase-user",
        name = "Cloud account",
        role = role,
        permissions = RolePermissions.of(role),
    )
}

private fun localCashierOptions(settings: com.xcloak.storevia.data.prefs.AppSettings) = CashierOptions(
    canDiscount = settings.cashierCanDiscount,
    canSellOnCredit = settings.cashierCanSellOnCredit,
    canOverrideBatch = settings.cashierCanOverrideBatch,
)
