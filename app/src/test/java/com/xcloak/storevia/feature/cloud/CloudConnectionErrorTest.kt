package com.xcloak.storevia.feature.cloud

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudConnectionErrorTest {
    @Test fun connectionErrorsShowConnectivityAdvice() {
        assertEquals(
            "No internet connection. Connect to the internet and try signing in again.",
            CloudConnectionError.message(UnknownHostException("Unable to resolve host")),
        )
    }

    @Test fun missingSupabaseRpcPointsToSchemaUpdate() {
        assertTrue(
            CloudConnectionError.message(IllegalStateException("PGRST202: Could not find the function public.my_store"))
                .contains("supabase/schema.sql"),
        )
    }

    @Test fun expiredSessionAsksForNewSignIn() {
        assertTrue(
            CloudConnectionError.message(IllegalStateException("JWT expired"))
                .contains("Sign out"),
        )
    }

    @Test fun unknownFailureShowsBoundedSanitizedDiagnostic() {
        val message = CloudConnectionError.message(
            IllegalStateException("Request failed https://project.supabase.co with access_token=secret-value"),
        )

        assertTrue(message.contains("[cloud endpoint]"))
        assertTrue(message.contains("[hidden]"))
        assertTrue(!message.contains("secret-value"))
        assertTrue(message.length < 260)
    }
}
