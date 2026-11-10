package com.xcloak.storevia.data.backup

import com.xcloak.storevia.data.prefs.StorageMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupPolicyTest {
    @Test
    fun localAutoBackupRunsOnlyForLocalMode() {
        assertTrue(shouldCreateLocalAutoBackup(StorageMode.LOCAL))
        assertFalse(shouldCreateLocalAutoBackup(StorageMode.CLOUD))
        assertFalse(shouldCreateLocalAutoBackup(null))
    }
}
