package com.xcloak.storevia.feature.settings

import com.xcloak.storevia.data.prefs.StorageMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRegistrationDraftTest {
    @Test
    fun usesLocalDraftWhenCloudStoreHasNotBeenCreated() {
        assertTrue(shouldUseLocalCloudRegistrationDraft(StorageMode.CLOUD, null))
    }

    @Test
    fun connectedCloudStoreDoesNotUseLocalDraft() {
        assertFalse(shouldUseLocalCloudRegistrationDraft(StorageMode.CLOUD, "store-id"))
    }

    @Test
    fun localModeDoesNotUseCloudRegistrationDraft() {
        assertFalse(shouldUseLocalCloudRegistrationDraft(StorageMode.LOCAL, null))
    }
}
