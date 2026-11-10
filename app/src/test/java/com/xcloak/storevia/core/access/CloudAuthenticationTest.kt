package com.xcloak.storevia.core.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAuthenticationTest {
    @Test
    fun keepsAuthenticatedActorDuringSessionInitialization() {
        assertTrue(
            resolveCloudAuthentication(
                isAuthenticated = false,
                isInitializing = true,
                hasSession = false,
                previouslyAuthenticated = true,
            ),
        )
    }

    @Test
    fun doesNotGrantAccessBeforeInitialSessionIsLoaded() {
        assertFalse(
            resolveCloudAuthentication(
                isAuthenticated = false,
                isInitializing = true,
                hasSession = false,
                previouslyAuthenticated = false,
            ),
        )
    }

    @Test
    fun explicitSignOutRevokesCloudAccess() {
        assertFalse(
            resolveCloudAuthentication(
                isAuthenticated = false,
                isInitializing = false,
                hasSession = false,
                previouslyAuthenticated = true,
            ),
        )
    }
}
