package com.xcloak.storevia.domain.imports

import com.xcloak.storevia.core.database.entity.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesImportParserTest {
    @Test
    fun parsesLinkedSummaryAndLineFilesWithAliases() {
        val result = SalesImportParser.parse(
            "Bill ID,Date,Customer,Payment Mode,Discount,Tax,Total\n" +
                "INV-1,2026-11-02,Asha,CASH,1.00,18.00,117.00\n",
            "Bill ID,SKU,Product Name,Qty,Unit Price\n" +
                "INV-1,S-1,Shirt,2,58.50\n",
        )

        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(1, result.summaries.size)
        assertEquals("INV-1", result.summaries.single().billId)
        assertEquals(PaymentMethod.CASH, result.summaries.single().paymentMethod)
        assertEquals(11_700L, result.summaries.single().totalPaise)
        assertEquals("S-1", result.lines.single().sku)
        assertEquals(5_850L, result.lines.single().unitPricePaise)
    }

    @Test
    fun flagsOrphanLinesMissingBillItemsAndInvalidPaymentMode() {
        val result = SalesImportParser.parse(
            "bill_id,date,payment_mode,total\n" +
                "A,2026-11-02,unknown,12\n" +
                "B,2026-11-02,CREDIT,12\n",
            "bill_id,sku,qty,unit_price\n" +
                "C,S-1,1,12\n",
        )

        assertFalse(result.errors.isEmpty())
        assertTrue(result.errors.any { it.message.contains("Payment mode") })
        assertTrue(result.errors.any { it.message.contains("missing from the sales summary") })
        assertTrue(result.errors.any { it.message.contains("no line items") })
        assertEquals(1, result.summaries.size)
        assertTrue(result.summaries.single().isCredit)
    }

    @Test
    fun rejectsDuplicateBillIdsAndInvalidQuantities() {
        val result = SalesImportParser.parse(
            "bill_id,date,payment_mode,total\n" +
                "A,2026-11-02,UPI,12\n" +
                "A,2026-11-02,UPI,12\n",
            "bill_id,product_name,qty,unit_price\n" +
                "A,Tea,0,12\n",
        )

        assertTrue(result.errors.any { it.message.contains("duplicated") })
        assertTrue(result.errors.any { it.message.contains("Quantity") })
    }
}
