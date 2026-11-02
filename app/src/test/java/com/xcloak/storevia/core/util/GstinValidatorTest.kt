package com.xcloak.storevia.core.util

import com.xcloak.storevia.core.util.GstinValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class GstinValidatorTest {
    private val sample = "27AAPFU0939F1ZV"   // widely used example GSTIN; Maharashtra

    @Test fun validGstinPasses() = assertEquals(Result.VALID, GstinValidator.validate(sample, "27"))
    @Test fun lowercaseAndSpacesAreNormalised() = assertEquals(Result.VALID, GstinValidator.validate("  27aapfu0939f1zv ", "27"))
    @Test fun wrongStateIsCaught() = assertEquals(Result.STATE_MISMATCH, GstinValidator.validate(sample, "29"))
    @Test fun typoIsCaughtByChecksum() = assertEquals(Result.BAD_CHECKSUM, GstinValidator.validate("27AAPFU0939F1ZW", "27"))
    @Test fun wrongLengthIsBadFormat() = assertEquals(Result.BAD_FORMAT, GstinValidator.validate("27AAPFU0939F1Z", "27"))
    @Test fun stateLookup() = assertEquals("Karnataka", GstStates.name("29"))
}
