package com.xcloak.storevia.data

import com.xcloak.storevia.data.prefs.StorageMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapStorageModeTest {
    @Test
    fun cloudModeNeverSeedsBusinessRowsIntoRoom() {
        assertFalse(shouldSeedWorkspace(StorageMode.CLOUD))
    }

    @Test
    fun localAndUnselectedSetupCanSeedWorkspaceDefaults() {
        assertTrue(shouldSeedWorkspace(StorageMode.LOCAL))
        assertTrue(shouldSeedWorkspace(null))
    }

    @Test
    fun cloudRegistrationCanSeedALocalDraftBeforeAStoreExists() {
        assertTrue(shouldSeedWorkspace(StorageMode.CLOUD, force = true))
    }
}
