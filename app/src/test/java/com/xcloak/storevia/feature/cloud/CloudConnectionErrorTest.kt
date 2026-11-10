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

    @Test fun missingSupabaseRpcNamesFunctionAndPreservesDetails() {
        val message = CloudConnectionError.message(
            IllegalStateException("PGRST202: Could not find the function public.my_store"),
        )

        assertTrue(message.contains("public.my_store"))
        assertTrue(message.contains("schema cache"))
        assertTrue(message.contains("PGRST202"))
    }

    @Test fun schemaCacheErrorsShowOperationAndServerDetails() {
        val message = CloudConnectionError.message(
            IllegalStateException(
                "Downloading cloud table 'staff' failed.",
                IllegalStateException("PGRST202: schema cache lookup failed for table staff"),
            ),
        )

        assertTrue(message.contains("schema cache"))
        assertTrue(message.contains("Downloading cloud table 'staff'"))
        assertTrue(message.contains("lookup failed for table staff"))
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

    @Test fun genericNestedFailureShowsUnderlyingCauseRatherThanOnlyWrapper() {
        val message = CloudConnectionError.message(
            IllegalStateException(
                "Synchronizing store data failed.",
                IllegalStateException(
                    "Uploading cloud table 'store' failed.",
                    IllegalStateException("duplicate key value violates unique constraint"),
                ),
            ),
        )

        assertTrue(message.contains("duplicate key value"))
        assertTrue(!message.endsWith("Synchronizing store data failed."))
    }

    @Test fun schemaCacheDiagnosticRedactsBearerTokensAndEndpoints() {
        val message = CloudConnectionError.message(
            IllegalStateException(
                "Loading store membership failed.",
                IllegalStateException(
                    "PGRST202 from https://project.supabase.co/rest/v1/rpc/my_store " +
                        "Authorization: Bearer abc.def.ghi apikey=secret-value",
                ),
            ),
        )

        assertTrue(message.contains("Loading store membership"))
        assertTrue(message.contains("[cloud endpoint]"))
        assertTrue(message.contains("[hidden]"))
        assertTrue(!message.contains("abc.def.ghi"))
        assertTrue(!message.contains("secret-value"))
        assertTrue(message.length < 360)
    }
}
