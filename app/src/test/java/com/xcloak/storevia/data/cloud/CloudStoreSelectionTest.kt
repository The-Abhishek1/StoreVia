package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.data.prefs.StorageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudStoreSelectionTest {
    @Test
    fun signupCreatesNewStoreInsteadOfChoosingAnExistingMembership() {
        assertEquals(
            CloudStoreSelection.CreateNew,
            selectCloudSignupStore(
                selectedStoreId = null,
                membershipStoreIds = listOf("other-store"),
            ),
        )
    }

    @Test
    fun signupRetryResumesItsPersistedStoreDestination() {
        assertEquals(
            CloudStoreSelection.Existing("signup-store"),
            selectCloudSignupStore(
                selectedStoreId = "signup-store",
                membershipStoreIds = listOf("other-store", "signup-store"),
            ),
        )
    }

    @Test
    fun platformAdminDenialIsDistinctFromAccessCheckFailure() {
        assertEquals(
            true,
            isPlatformAdminAccessDenied(IllegalStateException("Platform administrator access required.")),
        )
        assertEquals(
            true,
            isPlatformAdminAccessDenied(IllegalStateException("Request failed with HTTP 403")),
        )
        assertEquals(
            false,
            isPlatformAdminAccessDenied(IllegalStateException("Gateway timeout")),
        )
    }

    @Test
    fun persistedStoreIsPreferredWhenMultipleMembershipsExist() {
        assertEquals(
            "selected-store",
            autoSelectedCloudStoreId("selected-store", listOf("first-store", "selected-store")),
        )
    }

    @Test
    fun multipleMembershipsRequireAnExplicitChoice() {
        assertEquals(
            null,
            autoSelectedCloudStoreId(null, listOf("first-store", "second-store")),
        )
    }

    @Test
    fun aSingleMembershipCanBeSelectedAutomatically() {
        assertEquals(
            "only-store",
            autoSelectedCloudStoreId(null, listOf("only-store")),
        )
    }

    @Test
    fun cloudAccountSwitchClearsRoomCacheBeforePull() {
        assertEquals(true, shouldResetLocalCacheForCloudStore(false, null, "store-b"))
        assertEquals(true, shouldResetLocalCacheForCloudStore(false, "store-a", "store-b"))
        assertEquals(false, shouldResetLocalCacheForCloudStore(false, "store-a", "store-a"))
        assertEquals(false, shouldResetLocalCacheForCloudStore(true, "store-a", "store-b"))
    }

    @Test
    fun localDatasetCreatesANewStoreEvenWhenAccountHasMemberships() {
        assertEquals(
            CloudStoreSelection.CreateNew,
            selectCloudStore(
                storageMode = StorageMode.LOCAL,
                selectedStoreId = null,
                membershipStoreIds = listOf("existing-store"),
                createIfMissing = true,
            ),
        )
    }

    @Test
    fun unselectedLocalDatasetDoesNotReuseAnExistingMembership() {
        assertEquals(
            CloudStoreSelection.CreateNew,
            selectCloudStore(
                storageMode = null,
                selectedStoreId = null,
                membershipStoreIds = listOf("existing-store"),
                createIfMissing = true,
            ),
        )
    }

    @Test
    fun pendingLocalTransferResumesItsPersistedDestination() {
        assertEquals(
            CloudStoreSelection.Existing("new-store"),
            selectCloudStore(
                storageMode = StorageMode.LOCAL,
                selectedStoreId = "new-store",
                membershipStoreIds = listOf("existing-store", "new-store"),
                createIfMissing = true,
            ),
        )
    }

    @Test
    fun cloudReconnectUsesSelectedMembershipRatherThanFirstMembership() {
        assertEquals(
            CloudStoreSelection.Existing("selected-store"),
            selectCloudStore(
                storageMode = StorageMode.CLOUD,
                selectedStoreId = "selected-store",
                membershipStoreIds = listOf("older-store", "selected-store"),
                createIfMissing = true,
            ),
        )
    }

    @Test
    fun connectingAnExistingCloudStoreDoesNotRequireLocalStoreSetup() {
        assertEquals(
            false,
            requiresLocalStoreForConnection(
                CloudStoreSelection.Existing("cloud-store"),
                StorageMode.CLOUD,
            ),
        )
    }

    @Test
    fun creatingAStoreOrTransferringLocalDataStillRequiresLocalSetup() {
        assertEquals(
            true,
            requiresLocalStoreForConnection(CloudStoreSelection.CreateNew, StorageMode.CLOUD),
        )
        assertEquals(
            true,
            requiresLocalStoreForConnection(CloudStoreSelection.Existing("cloud-store"), StorageMode.LOCAL),
        )
    }

    @Test
    fun unselectedCloudMembershipsUseTheStableFirstResult() {
        assertEquals(
            CloudStoreSelection.Existing("first-store"),
            selectCloudStore(
                storageMode = StorageMode.CLOUD,
                selectedStoreId = null,
                membershipStoreIds = listOf("first-store", "second-store"),
                createIfMissing = false,
            ),
        )
    }

    @Test
    fun missingPersistedSelectionFailsInsteadOfSilentlySwitchingStores() {
        assertThrows(IllegalStateException::class.java) {
            selectCloudStore(
                storageMode = StorageMode.CLOUD,
                selectedStoreId = "selected-store",
                membershipStoreIds = listOf("other-store"),
                createIfMissing = true,
            )
        }
    }
}
