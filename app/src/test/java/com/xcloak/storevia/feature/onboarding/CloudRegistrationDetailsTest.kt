package com.xcloak.storevia.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRegistrationDetailsTest {
    @Test
    fun clearsDetailsOnlyAtTheStartOfFreshCloudSignup() {
        assertTrue(
            shouldClearCloudRegistrationDetails(
                cloudConnectRequired = true,
                clearRegistrationDetailsOnStart = true,
                loaded = true,
                step = 0,
            ),
        )
    }

    @Test
    fun storeSetupAndLaterStepsPreserveTheSavedSignupDetails() {
        assertFalse(
            shouldClearCloudRegistrationDetails(
                cloudConnectRequired = true,
                clearRegistrationDetailsOnStart = false,
                loaded = true,
                step = 0,
            ),
        )
        assertFalse(
            shouldClearCloudRegistrationDetails(
                cloudConnectRequired = true,
                clearRegistrationDetailsOnStart = true,
                loaded = true,
                step = 1,
            ),
        )
    }
}
