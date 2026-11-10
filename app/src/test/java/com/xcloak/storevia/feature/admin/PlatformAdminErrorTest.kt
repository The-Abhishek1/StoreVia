package com.xcloak.storevia.feature.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformAdminErrorTest {
    @Test
    fun unknownActionRequestsServiceUpdateWithoutExposingResponse() {
        val error = normalizePlatformAdminError(
            IllegalStateException(
                "Unknown action. URL: https://example.invalid/functions/v1/platform-admin Headers: Authorization=secret",
            ),
        )

        assertTrue(error is PlatformAdminServiceUpdateRequiredException)
        assertFalse(error.message.orEmpty().contains("Authorization"))
        assertFalse(error.message.orEmpty().contains("example.invalid"))
    }

    @Test
    fun gatewayErrorIsSanitizedAndStatesNoChange() {
        val error = normalizePlatformAdminError(
            IllegalStateException("<title>502 Bad Gateway</title> URL: https://example.invalid"),
        )

        assertEquals(
            "Platform admin request failed (HTTP 502). No change was made. Check the Supabase Edge Function and retry.",
            error.message,
        )
        assertFalse(error.message.orEmpty().contains("example.invalid"))
    }
}
