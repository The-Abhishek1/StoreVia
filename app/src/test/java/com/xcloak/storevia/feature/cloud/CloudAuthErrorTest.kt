package com.xcloak.storevia.feature.cloud

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudAuthErrorTest {
    @Test fun offlineFailureIsReportedAsConnectivityEvenIfRequestMentionsRedirect() {
        val error = RuntimeException(
            "Request failed with redirect_to parameter",
            UnknownHostException("Unable to resolve Supabase host"),
        )

        assertEquals(
            "No internet connection. Connect to the internet and try signing in again.",
            CloudAuthError.message(error),
        )
    }

    @Test fun invalidCredentialsGetTheAuthenticationMessage() {
        assertEquals(
            "The email/mobile number or password is incorrect.",
            CloudAuthError.message(IllegalStateException("Invalid login credentials")),
        )
    }

    @Test fun unconfirmedEmailGetsAnActionableSignInMessage() {
        assertEquals(
            "Verify your email address before signing in.",
            CloudAuthError.message(IllegalStateException("Email not confirmed")),
        )
    }

    @Test fun unrelatedErrorsAreLeftForTheCallerFallback() {
        assertEquals(null, CloudAuthError.message(IllegalStateException("Unexpected response")))
    }
}
