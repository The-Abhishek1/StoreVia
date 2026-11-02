package com.xcloak.storevia.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {
    @Test fun camelCaseBecomesSnakeCase() {
        assertEquals("qty_on_hand_milli", Naming.snake("qtyOnHandMilli"))
        assertEquals("gst_rate_bp", Naming.snake("gstRateBp"))
        assertEquals("id", Naming.snake("id"))
        assertEquals("is_active", Naming.snake("isActive"))
        assertEquals("cgst_paise", Naming.snake("cgstPaise"))
    }

    @Test fun tablesAreOrderedParentsFirst() {
        val names = SyncTables.all.map { it.name }
        fun before(a: String, b: String) = assertTrue("$a before $b", names.indexOf(a) < names.indexOf(b))
        before("products", "batches"); before("products", "product_barcodes"); before("sales", "sale_lines")
        before("sale_returns", "sale_return_lines"); before("purchases", "purchase_lines")
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun nothingThatStaysOnThePhoneIsSynced() {
        assertTrue("held_carts" !in SyncTables.all.map { it.name })
        assertTrue("sync_state" !in SyncTables.all.map { it.name })
        assertTrue("pinHash" in SyncTables.all.single { it.name == "staff" }.localOnly)
        assertTrue("nextInvoiceNo" in SyncTables.all.single { it.name == "store" }.localOnly)
    }
}
