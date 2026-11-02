package com.xcloak.storevia.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscountAllocatorTest {
    @Test fun proportionalWhenItDividesEvenly() =
        assertEquals(listOf(100L, 200L, 700L), DiscountAllocator.allocate(1_000, listOf(1_000, 2_000, 7_000)))

    @Test fun alwaysSumsToTheTotal() {
        for (total in 0L..200L) {
            val w = listOf(333L, 1_001L, 7L, 5_000L)
            val shares = DiscountAllocator.allocate(total, w)
            assertEquals(total, shares.sum())
            assertTrue(shares.indices.all { shares[it] <= w[it] })
        }
    }

    @Test fun leftoverPaiseGoToLargestRemainders() =
        // 1 paisa over three equal items: the first gets it
        assertEquals(listOf(1L, 0L, 0L), DiscountAllocator.allocate(1, listOf(100, 100, 100)))

    @Test fun zeroWeightItemsGetNothing() =
        assertEquals(listOf(0L, 50L, 0L), DiscountAllocator.allocate(50, listOf(0, 500, 0)))

    @Test fun fullDiscountTakesEverything() =
        assertEquals(listOf(333L, 667L), DiscountAllocator.allocate(1_000, listOf(333, 667)))

    @Test fun emptyOrZero() {
        assertEquals(emptyList<Long>(), DiscountAllocator.allocate(0, emptyList()))
        assertEquals(listOf(0L, 0L), DiscountAllocator.allocate(0, listOf(5, 5)))
    }
}
