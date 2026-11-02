package com.xcloak.storevia.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {
    @Test fun moneyParsing() {
        assertEquals(1250L, Parsing.moneyToPaise("12.5"))
        assertEquals(123450L, Parsing.moneyToPaise("1,234.50"))
        assertEquals(1L, Parsing.moneyToPaise("0.005"))
        assertNull(Parsing.moneyToPaise("abc"))
        assertNull(Parsing.moneyToPaise("-1"))
        assertNull(Parsing.moneyToPaise(""))
    }

    @Test fun quantityParsing() {
        assertEquals(1250L, Parsing.qtyToMilli("1.250", allowDecimal = true))
        assertEquals(2000L, Parsing.qtyToMilli("2", allowDecimal = false))
        assertNull(Parsing.qtyToMilli("1.5", allowDecimal = false))
        assertEquals(2000L, Parsing.qtyToMilli("2.0", allowDecimal = false))
    }

    @Test fun roundTripFormatting() {
        assertEquals("10", Parsing.paiseToInput(1000))
        assertEquals("10.50", Parsing.paiseToInput(1050))
        assertEquals("1.25", Parsing.milliToInput(1250))
        assertEquals("2", Parsing.milliToInput(2000))
        assertEquals("0", Parsing.milliToInput(0))
    }
}
