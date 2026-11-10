package com.xcloak.storevia.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageModeTest {
    @Test
    fun persistedStorageModeHasOnlyLocalAndCloudValues() {
        assertEquals(listOf("LOCAL", "CLOUD"), StorageMode.entries.map { it.name })
        assertEquals(StorageMode.LOCAL, AppSettings(storageMode = StorageMode.LOCAL).storageMode)
        assertEquals(StorageMode.CLOUD, AppSettings(storageMode = StorageMode.CLOUD).storageMode)
    }

    @Test
    fun legacyOfflinePreferencesMigrateToLocalMode() {
        assertEquals(StorageMode.LOCAL, legacyStorageMode("OFFLINE_PENDING", false, false))
        assertEquals(StorageMode.LOCAL, legacyStorageMode("OFFLINE", false, false))
        assertEquals(StorageMode.LOCAL, legacyStorageMode(null, true, false))
    }

    @Test
    fun legacyCloudSelectionAndConnectionMigrateWithoutUsingConnectivity() {
        assertEquals(StorageMode.CLOUD, legacyStorageMode("CLOUD_PENDING", false, false))
        assertEquals(StorageMode.CLOUD, legacyStorageMode("UNSELECTED", false, true))
        assertEquals(null, legacyStorageMode("CLOUD", false, false))
        assertEquals(null, legacyStorageMode("UNSELECTED", false, false))
    }
}
