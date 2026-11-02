package com.xcloak.storevia.core.security

import com.xcloak.storevia.data.prefs.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The relock rule, kept pure so it can be unit tested. */
object AppLockLogic {
    /**
     * @param stoppedAt when the app last went to the background (0 = unknown / just unlocked)
     * @return whether the app should now be locked
     */
    fun shouldLock(enabled: Boolean, alreadyLocked: Boolean, stoppedAt: Long, now: Long, graceMs: Long): Boolean {
        if (!enabled) return false
        if (alreadyLocked) return true
        return stoppedAt != 0L && now - stoppedAt >= graceMs
    }
}

/**
 * Locks the app on a cold start and after it has been in the background longer than the grace period.
 * Unlocking uses the phone's own screen lock (PIN, pattern, password or fingerprint) via [DeviceAuth],
 * so Storevia never stores or handles a PIN itself.
 */
@Singleton
class AppLock @Inject constructor(private val prefs: AppPrefs) {
    private val _locked = MutableStateFlow(prefs.settings.value.lockEnabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var stoppedAt = 0L

    fun onStop(now: Long = System.currentTimeMillis()) { stoppedAt = now }

    fun onStart(now: Long = System.currentTimeMillis()) {
        val s = prefs.settings.value
        _locked.value = AppLockLogic.shouldLock(s.lockEnabled, _locked.value, stoppedAt, now, s.lockGraceSeconds * 1000L)
    }

    fun unlock() { _locked.value = false; stoppedAt = 0L }
}
