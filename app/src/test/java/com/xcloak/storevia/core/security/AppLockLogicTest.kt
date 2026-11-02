package com.xcloak.storevia.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockLogicTest {
    private val grace = 60_000L

    @Test fun neverLocksWhenDisabled() =
        assertFalse(AppLockLogic.shouldLock(enabled = false, alreadyLocked = true, stoppedAt = 1, now = 10_000_000, graceMs = grace))

    @Test fun staysLockedUntilUnlocked() =
        assertTrue(AppLockLogic.shouldLock(true, true, 0, 5, grace))

    @Test fun quickReturnWithinGraceDoesNotLock() =
        assertFalse(AppLockLogic.shouldLock(true, false, stoppedAt = 100_000, now = 130_000, graceMs = grace))

    @Test fun longAbsenceLocks() =
        assertTrue(AppLockLogic.shouldLock(true, false, stoppedAt = 100_000, now = 160_000, graceMs = grace))

    @Test fun justUnlockedIgnoresThePauseCausedByTheSystemPinScreen() =
        assertFalse(AppLockLogic.shouldLock(true, false, stoppedAt = 0, now = 999_999_999, graceMs = grace))
}
