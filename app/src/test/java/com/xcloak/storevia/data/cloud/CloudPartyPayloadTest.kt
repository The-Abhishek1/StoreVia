package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.CustomerLedgerEntity
import com.xcloak.storevia.core.database.entity.LedgerType
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive

class CloudPartyPayloadTest {
    @Test
    fun all_party_rows_are_explicitly_scoped_to_the_selected_store() {
        val storeId = "selected-store"
        val payloads = listOf(
            CustomerEntity(id = "customer", name = "Customer").toCloudJson(storeId),
            SupplierEntity(id = "supplier", name = "Supplier").toCloudJson(storeId),
            CustomerLedgerEntity(customerId = "customer", type = LedgerType.ADJUSTMENT, amountPaise = 100).toCloudJson(storeId),
            SupplierLedgerEntity(supplierId = "supplier", type = SupplierLedgerType.ADJUSTMENT, amountPaise = 100).toCloudJson(storeId),
        )

        payloads.forEach { payload ->
            assertEquals(storeId, payload.getValue("store_id").jsonPrimitive.content)
            assertFalse(payload.containsKey("dirty"))
        }
    }

    @Test
    fun ledger_payloads_use_cloud_column_names() {
        val customer = CustomerLedgerEntity(
            customerId = "customer",
            type = LedgerType.ADJUSTMENT,
            amountPaise = 250,
            refType = "MANUAL",
        ).toCloudJson("shop")
        val supplier = SupplierLedgerEntity(
            supplierId = "supplier",
            type = SupplierLedgerType.PAYMENT,
            amountPaise = -250,
            refType = "SUPPLIER_BALANCE",
        ).toCloudJson("shop")

        assertEquals("customer", customer.getValue("customer_id").jsonPrimitive.content)
        assertEquals("ADJUSTMENT", customer.getValue("type").jsonPrimitive.content)
        assertEquals("supplier", supplier.getValue("supplier_id").jsonPrimitive.content)
        assertEquals("PAYMENT", supplier.getValue("type").jsonPrimitive.content)
    }
}
