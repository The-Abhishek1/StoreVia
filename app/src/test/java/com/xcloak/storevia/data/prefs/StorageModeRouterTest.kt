package com.xcloak.storevia.data.prefs

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StorageModeRouterTest {
    @Test
    fun localModeKeepsLocalImportExportSource() {
        var localCalls = 0
        var cloudCalls = 0

        val result = StorageModeRouter.route(
            StorageMode.LOCAL,
            local = { localCalls++; "room" },
            cloud = { cloudCalls++; "supabase" },
        )

        assertEquals("room", result)
        assertEquals(1, localCalls)
        assertEquals(0, cloudCalls)
    }

    @Test
    fun cloudModeNeverFallsBackToRoomWhenRemoteFails() {
        var localCalls = 0
        var cloudCalls = 0

        var failure: Throwable? = null
        runTest {
            runCatching {
                StorageModeRouter.routeSuspend(
                    StorageMode.CLOUD,
                    local = { localCalls++; "room cache" },
                    cloud = { cloudCalls++; throw IllegalStateException("Supabase unavailable") },
                )
            }.onFailure { failure = it }
        }

        assertEquals("Supabase unavailable", failure?.message)
        assertEquals(0, localCalls)
        assertEquals(1, cloudCalls)
    }

    @Test
    fun observingCloudModeUsesOnlyCloudFlow() = runTest {
        var localCalls = false
        var cloudCalls = false

        val result = StorageModeRouter.observe(
            flowOf(StorageMode.CLOUD),
            local = { localCalls = true; flowOf("room") },
            cloud = { cloudCalls = true; flowOf("supabase") },
        ).first()

        assertEquals("supabase", result)
        assertFalse(localCalls)
        assertEquals(true, cloudCalls)
    }
}
