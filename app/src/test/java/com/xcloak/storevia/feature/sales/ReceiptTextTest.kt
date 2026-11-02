package com.xcloak.storevia.feature.sales

import com.xcloak.storevia.core.database.entity.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTextTest {
    private fun line(batch: String?, qty: Long, total: Long) = SaleLineEntity(
        saleId = "s", productId = "p1", batchId = batch, nameSnapshot = "Biscuits", qtyMilli = qty,
        unitPricePaise = 1000, discountPaise = 0, gstRateBp = 1800, taxInclusive = true,
        taxableValuePaise = total * 100 / 118, cgstPaise = 0, sgstPaise = 0, igstPaise = 0,
        lineTotalPaise = total, costSnapshotPaise = 700,
    )

    @Test fun linesFromSeveralBatchesMergeIntoOneItem() {
        val merged = ReceiptText.mergeLines(listOf(line("a", 5_000, 5_000), line("b", 2_000, 2_000)))
        assertEquals(1, merged.size)
        assertEquals(7_000L, merged[0].qtyMilli)
        assertEquals(7_000L, merged[0].amountPaise)
    }

    @Test fun receiptShowsDueAndInvoice() {
        val store = StoreEntity(name = "Ravi Stores", gstin = "29ABCDE1234F1Z5")
        val sale = SaleEntity(
            invoiceNo = "INV-00007", subtotalPaise = 1_695, discountPaise = 0, taxPaise = 305,
            roundOffPaise = 0, totalPaise = 2_000, paidPaise = 1_000,
        )
        val text = ReceiptText.format(store, sale, listOf(line("a", 2_000, 2_000)), emptyList(), "Ravi")
        assertTrue(text.contains("INV-00007"))
        assertTrue(text.contains("GSTIN: 29ABCDE1234F1Z5"))
        assertTrue(text.contains("Balance due: ₹10.00"))
    }
}
