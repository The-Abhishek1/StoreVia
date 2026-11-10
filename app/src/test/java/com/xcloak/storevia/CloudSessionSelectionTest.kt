package com.xcloak.storevia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSessionSelectionTest {
    @Test
    fun authenticatedUsersAreCheckedForPlatformAdminAccess() {
        assertTrue(shouldCheckPlatformAdminAccess(true))
    }

    @Test
    fun platformAdminCheckDoesNotRunWithoutAuthentication() {
        assertFalse(shouldCheckPlatformAdminAccess(false))
    }

    @Test
    fun verifiedPendingCloudSignupResumesStoreCreationWithoutMembershipLookup() {
        assertTrue(
            shouldResumeCloudSignup(
                authenticated = true,
                platformAdmin = false,
                signupPending = true,
                selectedStoreId = null,
            ),
        )
    }

    @Test
    fun pendingCloudSignupDoesNotOverrideAdminOrExistingStore() {
        assertFalse(
            shouldResumeCloudSignup(
                authenticated = true,
                platformAdmin = true,
                signupPending = true,
                selectedStoreId = null,
            ),
        )
        assertFalse(
            shouldResumeCloudSignup(
                authenticated = true,
                platformAdmin = false,
                signupPending = true,
                selectedStoreId = "store-id",
            ),
        )
    }
}
