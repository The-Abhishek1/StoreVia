package com.xcloak.storevia.core.money

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyAndGstTest {

    @Test fun formatsIndianGrouping() {
        assertEquals("₹1,23,456.70", Money(12_345_670).format())
        assertEquals("₹999.05", Money(99_905).format())
        assertEquals("-₹10.00", Money(-1000).format())
    }

    @Test fun quantityTimesPriceRoundsHalfUp() {
        // 1.250 kg @ ₹33.33/kg = 41.6625 -> 41.66
        assertEquals(4166L, Money(3333).timesQtyMilli(1250).paise)
    }

    @Test fun exclusiveIntraStateSplitsEvenly() {
        val t = GstCalculator.calculate(10_000, 1800, taxInclusive = false, interState = false)
        assertEquals(10_000, t.taxableValue)
        assertEquals(900, t.cgst); assertEquals(900, t.sgst); assertEquals(0, t.igst)
        assertEquals(11_800, t.lineTotal)
    }

    @Test fun inclusiveBackCalculatesTaxable() {
        // ₹118 inclusive of 18% -> taxable 100, tax 18
        val t = GstCalculator.calculate(11_800, 1800, taxInclusive = true, interState = true)
        assertEquals(10_000, t.taxableValue)
        assertEquals(1_800, t.igst)
        assertEquals(11_800, t.lineTotal)
    }

    @Test fun oddPaisaGoesToSgstAndTotalsReconcile() {
        // 5% of ₹1.01 = 5.05 -> 5 paise; split 2 + 3
        val t = GstCalculator.calculate(101, 500, taxInclusive = false, interState = false)
        assertEquals(t.totalTax, t.cgst + t.sgst)
        assertEquals(2, t.cgst); assertEquals(3, t.sgst)
    }

    @Test fun inclusiveAlwaysReconcilesToAmount() {
        for (amt in 1L..5000L) for (rate in listOf(0, 500, 1200, 1800, 2800)) {
            val t = GstCalculator.calculate(amt, rate, true, false)
            assertEquals(amt, t.taxableValue + t.totalTax)
        }
    }

    @Test fun roundOffToRupee() {
        assertEquals(10_000L to -49L, GstCalculator.roundToRupee(10_049))
        assertEquals(10_100L to 1L, GstCalculator.roundToRupee(10_099))
    }
}
