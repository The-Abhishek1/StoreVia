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
        val indexes = names.withIndex().associate { it.value to it.index }
        SyncTables.all.forEach { table ->
            table.dependsOn.forEach { dependency ->
                assertTrue("$dependency before ${table.name}", indexes.getValue(dependency) < indexes.getValue(table.name))
            }
        }
        assertTrue("serial_numbers depends on its product and batch", SyncTables.all.single { it.name == "serial_numbers" }
            .dependsOn.containsAll(setOf("products", "batches")))
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun deviceSpecificSettingsStayLocalAndHeldCartsAreSynced() {
        assertTrue("held_carts" in SyncTables.all.map { it.name })
        assertTrue("held_carts are ordered after their serialized dependencies", SyncTables.all.single { it.name == "held_carts" }
            .dependsOn.containsAll(setOf("customers", "products", "batches")))
        assertTrue("sync_state" !in SyncTables.all.map { it.name })
        assertTrue("pinHash" in SyncTables.all.single { it.name == "staff" }.localOnly)
        assertTrue("nextInvoiceNo" in SyncTables.all.single { it.name == "store" }.localOnly)
        assertTrue("selectedOnDevice" in SyncTables.all.single { it.name == "branches" }.localOnly)
    }
}
