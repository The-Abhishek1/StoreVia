package com.xcloak.storevia.domain.sale

import com.xcloak.storevia.core.database.entity.BatchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreferredBatchOrderTest {
    private val firstFefo = BatchEntity(
        id = "first",
        productId = "product",
        qtyReceivedMilli = 1_000,
        qtyOnHandMilli = 1_000,
    )
    private val laterFefo = BatchEntity(
        id = "later",
        productId = "product",
        qtyReceivedMilli = 1_000,
        qtyOnHandMilli = 1_000,
    )

    @Test
    fun automaticAllocationPreservesFefoOrder() {
        assertEquals(listOf(firstFefo, laterFefo), preferredBatchOrder(listOf(firstFefo, laterFefo), null))
    }

    @Test
    fun authorizedManualSelectionRunsFirstThenFallsBackToFefo() {
        assertEquals(listOf(laterFefo, firstFefo), preferredBatchOrder(listOf(firstFefo, laterFefo), "later"))
    }

    @Test
    fun unavailableOrExpiredBatchCannotBeSelected() {
        assertThrows(SaleValidationException::class.java) {
            preferredBatchOrder(listOf(firstFefo), "expired")
        }
    }
}
