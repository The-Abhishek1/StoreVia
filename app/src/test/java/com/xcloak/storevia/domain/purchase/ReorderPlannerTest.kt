package com.xcloak.storevia.domain.purchase

import com.xcloak.storevia.core.database.dao.ReorderRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderPlannerTest {
    @Test fun topsUpToTwiceTheReorderLevel() =
        assertEquals(17_000L, ReorderPlanner.suggestMilli(onHandMilli = 3_000, minStockMilli = 10_000, allowDecimal = false))

    @Test fun nothingToBuyWhenStockIsAtOrAboveTarget() {
        assertEquals(0L, ReorderPlanner.suggestMilli(20_000, 10_000, false))
        assertEquals(0L, ReorderPlanner.suggestMilli(25_000, 10_000, false))
    }

    @Test fun roundsUpToWholeUnitsOrHalfKilos() {
        assertEquals(2_000L, ReorderPlanner.suggestMilli(1_500, 1_750, false))     // need 2000
        assertEquals(4_500L, ReorderPlanner.suggestMilli(900, 2_700, true))        // need 4500
        assertEquals(1_000L, ReorderPlanner.suggestMilli(500, 750, false))         // need 1000
        assertEquals(1_500L, ReorderPlanner.suggestMilli(100, 800, true))          // need 1500
        assertEquals(2_000L, ReorderPlanner.suggestMilli(0, 600, false))           // need 1200 -> 2 whole units
    }

    @Test fun shareTextListsItemsWithSuggestedQuantities() {
        val rows = listOf(
            ReorderRow("1", "Tea", "kg", true, 500, 2_000, 0),
            ReorderRow("2", "Biscuits", "pkt", false, 2_000, 10_000, 0),
        )
        val t = ReorderPlanner.shareText("Ravi Stores", rows)
        assertTrue(t.startsWith("Reorder list - Ravi Stores"))
        assertTrue(t.contains("1. Tea - 3.5 kg"))
        assertTrue(t.contains("2. Biscuits - 18 pkt"))
    }
}
