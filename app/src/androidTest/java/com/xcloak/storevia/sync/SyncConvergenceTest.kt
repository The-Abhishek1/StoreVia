package com.xcloak.storevia.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.HeldCartEntity
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.core.database.entity.PurchaseEntity
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SaleStatus
import com.xcloak.storevia.core.database.entity.SerialNumberEntity
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import com.xcloak.storevia.core.sync.SyncTables
import com.xcloak.storevia.core.sync.SyncRemote
import com.xcloak.storevia.domain.purchase.PaySupplierUseCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Simulated phones selling and editing at the same time, syncing through a fake server. */
@RunWith(AndroidJUnit4::class)
class SyncConvergenceTest {
    private val remote = FakeRemote()
    private val a = Phone("")
    private val b = Phone("B")
    private val c = Phone("C")

    @After fun tearDown() { a.close(); b.close(); c.close() }

    private suspend fun syncAll() { a.engine(remote).sync(); b.engine(remote).sync(); a.engine(remote).sync() }

    @Test fun twoPhonesSellingOfflineEndWithTheSameStockAndDistinctInvoices() = runBlocking {
        val p = a.seedShop()
        a.sellPieces(p, 2)
        a.engine(remote).sync(); b.engine(remote).sync()
        assertEquals("8000", b.rows("SELECT qtyOnHandMilli FROM batches").single().single())      // phone B downloaded the shop and the stock

        // both sell while "offline", from the same 8 pieces
        a.sellPieces(p, 4)
        b.sellPieces(p, 3)
        syncAll()

        assertEquals(a.snapshot(), b.snapshot())
        assertEquals("1000", a.rows("SELECT qtyOnHandMilli FROM batches").single().single())      // 10 - 2 - 4 - 3: nobody's sale was lost
        assertEquals(3, a.rows("SELECT id FROM sales").size)
        assertEquals(listOf("INV-00001", "INV-00002", "INV-B-00001"), a.rows("SELECT invoiceNo FROM sales ORDER BY invoiceNo").map { it.single() })
    }

    @Test fun returnsAndKhataPaymentsMadeOnAnotherPhoneConverge() = runBlocking {
        val p = a.seedShop()
        val ravi = CustomerEntity(name = "Ravi"); a.db.customerDao().insert(ravi)
        val saleId = a.sellPieces(p, 3, paid = 1_000, customerId = ravi.id)                       // ₹30 bill, ₹10 paid, ₹20 on khata
        a.engine(remote).sync(); b.engine(remote).sync()

        b.takePayment(ravi.id, 500, com.xcloak.storevia.core.database.entity.PaymentMethod.CASH)  // ₹5 received on phone B
        b.giveBack(saleId, listOf(com.xcloak.storevia.domain.sale.ReturnItem(p.id, 1_000)))       // one piece comes back to phone B
        b.engine(remote).sync(); a.engine(remote).sync()

        assertEquals(a.snapshot(), b.snapshot())
        val sale = a.db.saleDao().getById(saleId)!!
        assertEquals(1_500L, sale.paidPaise); assertEquals(1_000L, sale.returnedPaise); assertEquals(SaleStatus.PARTIAL_RETURN, sale.status)
        assertEquals(500L, a.db.customerDao().balance(ravi.id))                                   // owes ₹5, the same on both phones
        assertEquals(500L, sale.totalPaise - sale.returnedPaise - sale.paidPaise)
    }

    @Test fun editsToTheSameProductEndAsOneVersion() = runBlocking {
        val p = a.seedShop()
        a.engine(remote).sync(); b.engine(remote).sync()

        a.db.productDao().update(a.db.productDao().getById(p.id)!!.copy(sellPricePaise = 1_200, updatedAt = 1, dirty = true))
        b.db.productDao().update(b.db.productDao().getById(p.id)!!.copy(name = "Biscuits XL", updatedAt = 2, dirty = true))
        syncAll()

        val onA = a.db.productDao().getById(p.id)!!; val onB = b.db.productDao().getById(p.id)!!
        assertEquals(onA, onB)                                  // one version everywhere
        assertEquals("Biscuits XL", onA.name)                   // last to reach the server wins (phone B synced last)
    }

    @Test fun syncingAgainSendsNothingAndChangesNothing() = runBlocking {
        val p = a.seedShop(); a.sellPieces(p, 2)
        val first = a.engine(remote).sync()
        val before = a.snapshot(); val rowsOnServer = remote.count("stock_movements")
        val second = a.engine(remote).sync()

        assertTrue(first.pushed > 0); assertEquals(0, second.pushed)
        assertEquals(before, a.snapshot()); assertEquals(rowsOnServer, remote.count("stock_movements"))
    }

    @Test fun batchesArriveBeforeSerialNumbersAndHeldCartJsonIsPreserved() = runBlocking {
        val p = a.seedShop()
        val batchId = a.rows("SELECT id FROM batches").single().single()
        val customer = CustomerEntity(name = "Ravi")
        a.db.customerDao().insert(customer)
        a.db.serialNumberDao().insertAll(listOf(SerialNumberEntity(
            productId = p.id, batchId = batchId, serialNumber = "SERIAL-001",
        )))
        val serializedItems = """[{"productId":"${p.id}","qtyMilli":1000,"unitPricePaise":1000,"preferredBatchId":"$batchId"}]"""
        val held = HeldCartEntity(
            label = customer.name,
            customerId = customer.id,
            itemsJson = serializedItems,
            itemCount = 1,
        )
        a.db.heldCartDao().insert(held)

        a.engine(remote).sync()
        b.engine(remote).sync()

        assertEquals(1, b.rows("SELECT id FROM serial_numbers").size)
        val received = b.db.heldCartDao().getById(held.id)!!
        assertEquals(customer.id, received.customerId)
        assertEquals(serializedItems, received.itemsJson)
        assertEquals(1, remote.count("held_carts"))

        b.db.heldCartDao().delete(held.id, System.currentTimeMillis())
        b.engine(remote).sync()
        a.engine(remote).sync()
        assertNull(a.db.heldCartDao().getById(held.id))
        assertTrue(a.rows("SELECT id FROM held_carts WHERE id = '${held.id}' AND deletedAt IS NOT NULL").isNotEmpty())
    }

    @Test fun allocatedSupplierPaymentUpdatesPurchaseOnOtherPhone() = runBlocking {
        val supplier = SupplierEntity(name = "Supplier")
        a.db.supplierDao().insert(supplier)
        val purchase = PurchaseEntity(
            supplierId = supplier.id,
            purchaseDate = 1L,
            totalPaise = 1_000,
            paidPaise = 0,
        )
        a.db.purchaseDao().insert(purchase)
        a.db.supplierDao().insertLedger(
            SupplierLedgerEntity(
                supplierId = supplier.id,
                type = SupplierLedgerType.PURCHASE,
                amountPaise = purchase.totalPaise,
                refType = "PURCHASE",
                refId = purchase.id,
            ),
        )
        a.engine(remote).sync()
        b.engine(remote).sync()

        PaySupplierUseCase(a.db, a.db.supplierDao())(
            supplier.id, 400, PaymentMethod.CASH, purchaseId = purchase.id,
        )
        a.engine(remote).sync()
        b.engine(remote).sync()

        assertEquals(400L, a.db.purchaseDao().getById(purchase.id)!!.paidPaise)
        assertEquals(400L, b.db.purchaseDao().getById(purchase.id)!!.paidPaise)
    }

    @Test fun transferringLocalDatasetUploadsEveryTableAndVerifiesTheCloudSnapshot() = runBlocking {
        val p = a.seedShop()
        a.sellPieces(p, 2)
        val engine = a.engine(remote)

        val result = engine.transferLocalDatasetAndVerify()

        assertTrue(result.pushed > 0)
        SyncTables.all.forEach { table ->
            assertEquals(table.name, a.rows("SELECT id FROM ${table.name}").size, remote.count(table.name))
            assertEquals(table.name, 0, a.rows("SELECT id FROM ${table.name} WHERE dirty = 1").size)
        }
    }

    @Test fun cloudRefreshNeverUploadsDirtyRoomRows() = runBlocking {
        val product = a.seedShop()
        a.db.productDao().update(product.copy(name = "Room-only edit", dirty = true))

        val result = a.engine(remote).pullOnly()

        assertEquals(0, result.pushed)
        assertEquals(0, remote.count("products"))
        assertEquals(1, a.rows("SELECT id FROM products WHERE dirty = 1").size)
    }

    @Test fun transferDoesNotActivateOnServerDataThatDiffersFromTheLocalSnapshot() = runBlocking {
        val p = a.seedShop()
        val tamperingRemote = object : SyncRemote {
            override suspend fun push(table: String, rows: List<JsonObject>, ignoreDuplicates: Boolean) {
                remote.push(table, rows, ignoreDuplicates)
            }

            override suspend fun pull(table: String, afterTs: String?, afterId: String, limit: Int): List<JsonObject> =
                remote.pull(table, afterTs, afterId, limit).map { row ->
                    if (table == "products" && row["id"] == JsonPrimitive(p.id)) {
                        JsonObject(row + ("name" to JsonPrimitive("server-mismatch")))
                    } else row
                }
        }

        try {
            a.engine(tamperingRemote).transferLocalDatasetAndVerify()
            fail("Transfer verification must reject a cloud row that differs from the original local dataset.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("products"))
        }
    }

    @Test fun aNewPhoneRebuildsTheSameNumbersFromTheRecords() = runBlocking {
        val p = a.seedShop()
        val ravi = CustomerEntity(name = "Ravi"); a.db.customerDao().insert(ravi)
        val s1 = a.sellPieces(p, 3)
        a.giveBack(s1, listOf(com.xcloak.storevia.domain.sale.ReturnItem(p.id, 1_000)))                          // partial return, cash refund
        val s2 = a.sellPieces(p, 2); a.cancel(s2, "wrong item")                                                    // cancelled bill
        a.sellPieces(p, 2, paid = 500, customerId = ravi.id)                                                       // credit sale
        a.takePayment(ravi.id, 300, com.xcloak.storevia.core.database.entity.PaymentMethod.UPI)                    // khata payment
        a.adjust(a.db.batchDao().fefoBatches(p.id, java.time.LocalDate.now()).first().id, MovementType.DAMAGE, 1_000, "dropped")           // damaged piece
        a.engine(remote).sync()

        c.engine(remote).sync()                                                                                    // a brand-new phone
        assertEquals(a.snapshot(), c.snapshot())
        assertEquals(SaleStatus.CANCELLED, c.db.saleDao().getById(s2)!!.status)
        assertEquals(SaleStatus.PARTIAL_RETURN, c.db.saleDao().getById(s1)!!.status)
    }

    @Test fun resettingAPhoneThrowsAwayLocalOnlyDataAndDownloadsTheCloudCopy() = runBlocking {
        val p = a.seedShop(); a.sellPieces(p, 2); a.engine(remote).sync()
        c.seedShop().let { c.sellPieces(it, 1) }                                  // phone C has its own, different, unsynced history
        val engine = c.engine(remote)
        engine.resetLocalData()
        assertEquals(0, c.rows("SELECT id FROM products").size)
        engine.sync()
        assertEquals(a.snapshot(), c.snapshot())
    }
}
