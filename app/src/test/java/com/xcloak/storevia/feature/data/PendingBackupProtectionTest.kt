package com.xcloak.storevia.feature.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingBackupProtectionTest {
    @Test fun noRequestIsDifferentFromAnExplicitUnencryptedBackup() {
        val pending = PendingBackupProtection()
        assertNull(pending.consume())

        pending.prepare(null)
        assertEquals(BackupProtection(null), pending.consume())
        assertNull(pending.consume())
    }

    @Test fun passphraseIsConsumedOnlyOnce() {
        val pending = PendingBackupProtection()
        pending.prepare("secret")

        assertEquals(BackupProtection("secret"), pending.consume())
        assertNull(pending.consume())
    }

    @Test fun cancelClearsThePendingPassphrase() {
        val pending = PendingBackupProtection()
        pending.prepare("secret")
        pending.clear()

        assertNull(pending.consume())
    }
}
