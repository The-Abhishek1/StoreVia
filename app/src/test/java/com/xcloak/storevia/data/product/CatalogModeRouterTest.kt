package com.xcloak.storevia.data.product

import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.data.prefs.StorageModeRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.cash.turbine.test

class CatalogModeRouterTest {
    @Test fun localModeUsesOnlyRoomOperation() {
        var localCalls = 0
        var cloudCalls = 0

        val result = StorageModeRouter.route(
            StorageMode.LOCAL,
            local = { localCalls++; "room result" },
            cloud = { cloudCalls++; "cloud result" },
        )

        assertEquals("room result", result)
        assertEquals(1, localCalls)
        assertEquals(0, cloudCalls)
    }

    @Test fun cloudModeUsesOnlySupabaseOperation() {
        var localCalls = 0
        var cloudCalls = 0

        val result = StorageModeRouter.route(
            StorageMode.CLOUD,
            local = { localCalls++; "room result" },
            cloud = { cloudCalls++; "cloud result" },
        )

        assertEquals("cloud result", result)
        assertEquals(0, localCalls)
        assertEquals(1, cloudCalls)
    }

    @Test fun missingModeRetainsLegacyLocalBehavior() {
        var localCalled = false
        var cloudCalled = false

        StorageModeRouter.route(
            null,
            local = { localCalled = true },
            cloud = { cloudCalled = true },
        )

        assertTrue(localCalled)
        assertFalse(cloudCalled)
    }

    @Test fun cloudFailureIsPropagatedWithoutTryingRoom() {
        var localCalls = 0

        try {
            StorageModeRouter.route(
                StorageMode.CLOUD,
                local = { localCalls++; "room result" },
                cloud = { throw IllegalStateException("Supabase unavailable") },
            )
            throw AssertionError("Expected cloud failure to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("Supabase unavailable", error.message)
        }

        assertEquals(0, localCalls)
    }

    @Test fun observeSwitchesSourcesWhenPersistedModeChanges() = runTest {
        val mode = MutableStateFlow<StorageMode?>(StorageMode.LOCAL)
        val routed = StorageModeRouter.observe(
            mode = mode,
            local = { flowOf("room") },
            cloud = { flowOf("supabase") },
        )

        routed.test {
            assertEquals("room", awaitItem())
            mode.value = StorageMode.CLOUD
            assertEquals("supabase", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
