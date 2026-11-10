package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.domain.purchase.*
import com.xcloak.storevia.domain.sale.CartItem
import com.xcloak.storevia.domain.sale.CompleteSaleUseCase
import com.xcloak.storevia.domain.sale.PaymentInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Product: ₹10 selling price, cost ₹5 before any purchase. 1000 = 1 piece. */
@RunWith(AndroidJUnit4::class)
class PurchaseUseCasesTest {
    private lateinit var db: StorviaDatabase
    private lateinit var purchase: RecordPurchaseUseCase
    private lateinit var paySupplier: PaySupplierUseCase
    private lateinit var product: ProductEntity
    private lateinit var tracked: ProductEntity
    private lateinit var supplier: SupplierEntity
    private val dayMillis = 1_760_000_000_000L

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        purchase = RecordPurchaseUseCase(db, db.productDao(), db.batchDao(), db.stockMovementDao(), db.purchaseDao(), db.supplierDao())
        paySupplier = PaySupplierUseCase(db, db.supplierDao())
        db.storeDao().upsert(StoreEntity(name = "Test"))
        db.branchDao().insert(BranchEntity(id = "default", name = "Main", selectedOnDevice = true))
        db.branchDao().insert(BranchEntity(id = "branch-2", name = "Second"))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        product = ProductEntity(name = "Biscuits", unitId = unit.id, sellPricePaise = 1000, costPaise = 500)
        tracked = ProductEntity(name = "Milk", unitId = unit.id, sellPricePaise = 600, trackExpiry = true)
        db.productDao().insert(product); db.productDao().insert(tracked)
        supplier = SupplierEntity(name = "Sharma Traders"); db.supplierDao().insert(supplier)
        Unit
    }

    @After fun tearDown() = db.close()

    private suspend fun buy(vararg items: PurchaseItem, supplierId: String? = supplier.id, paid: Long = 0, bill: Long? = null) =
        purchase(items.toList(), supplierId, "INV-9", dayMillis, billTotalPaise = bill, paidPaise = paid, payMethod = PaymentMethod.UPI)

    @Test fun purchaseCreatesBatchLedgerAndPayable() = runBlocking {
        val id = buy(PurchaseItem(product.id, 10_000, 700, "B1"), paid = 2_000)       // 10 pcs @ ₹7 = ₹70, ₹20 paid
        val p = db.purchaseDao().getById(id)!!
        assertEquals(7_000L, p.totalPaise); assertEquals(2_000L, p.paidPaise)
        assertEquals(5_000L, db.supplierDao().balance(supplier.id))                    // you owe ₹50
        val batch = db.batchDao().observeForProduct(product.id).first().single()
        assertEquals(700L, batch.costPaise); assertEquals(10_000L, batch.qtyOnHandMilli)
        assertEquals(10_000L, db.stockMovementDao().ledgerTotal(product.id))
        assertEquals(700L, db.productDao().getById(product.id)!!.costPaise)           // catalogue cost follows the latest purchase
    }

    @Test fun billTotalCanIncludeFreightWithoutChangingBatchCost() = runBlocking {
        val id = buy(PurchaseItem(product.id, 10_000, 700), bill = 7_500)
        assertEquals(7_500L, db.purchaseDao().getById(id)!!.totalPaise)
        assertEquals(7_500L, db.supplierDao().balance(supplier.id))
        assertEquals(700L, db.batchDao().observeForProduct(product.id).first().single().costPaise)
    }

    @Test fun cashPurchaseWithoutSupplierMustBePaidInFull() = runBlocking {
        buy(PurchaseItem(product.id, 1_000, 700), supplierId = null, paid = 700)         // fine
        try { buy(PurchaseItem(product.id, 1_000, 700), supplierId = null, paid = 0); fail() }
        catch (e: PurchaseException) { assertEquals(PurchaseException.Reason.SUPPLIER_REQUIRED_FOR_CREDIT, e.reason) }
    }

    @Test fun expiryIsRequiredForExpiryTrackedProducts() = runBlocking {
        try { buy(PurchaseItem(tracked.id, 1_000, 400)); fail() }
        catch (e: PurchaseException) { assertEquals(PurchaseException.Reason.EXPIRY_REQUIRED, e.reason) }
        buy(PurchaseItem(tracked.id, 1_000, 400, "M1", LocalDate.of(2027, 1, 1)))
        assertEquals(1, db.batchDao().observeForProduct(tracked.id).first().size)
    }

    @Test fun aFailedPurchaseChangesNothing() = runBlocking {
        try { buy(PurchaseItem(product.id, 5_000, 700), PurchaseItem(tracked.id, 1_000, 400)); fail() } catch (_: PurchaseException) { }
        assertEquals(0L, db.stockMovementDao().ledgerTotal(product.id))                  // the valid first line was rolled back too
        assertEquals(500L, db.productDao().getById(product.id)!!.costPaise)
        assertEquals(0L, db.supplierDao().balance(supplier.id))
    }

    @Test fun payingTheSupplierReducesWhatYouOweAndCannotOverpay() = runBlocking {
        val purchaseId = buy(PurchaseItem(product.id, 10_000, 700))                     // owe ₹70
        paySupplier(supplier.id, 3_000, PaymentMethod.CASH, purchaseId = purchaseId)
        assertEquals(4_000L, db.supplierDao().balance(supplier.id))
        assertEquals(3_000L, db.purchaseDao().getById(purchaseId)!!.paidPaise)
        try { paySupplier(supplier.id, 4_001, PaymentMethod.CASH); fail() }
        catch (e: SupplierPaymentException) { assertEquals(SupplierPaymentException.Reason.EXCEEDS_BALANCE, e.reason) }
        assertEquals(4_000L, db.supplierDao().observeTotalPayable().first())
    }

    @Test fun supplierPaymentReducesOnlyTheSelectedPurchaseAndUnpaidFilter() = runBlocking {
        val first = buy(PurchaseItem(product.id, 10_000, 700))
        val second = buy(PurchaseItem(product.id, 10_000, 900))

        paySupplier(supplier.id, 7_000, PaymentMethod.UPI, purchaseId = first)

        assertEquals(7_000L, db.purchaseDao().getById(first)!!.paidPaise)
        assertEquals(0L, db.purchaseDao().getById(second)!!.paidPaise)
        val unpaid = db.purchaseDao().observeRecent(unpaidOnly = true).first()
        assertEquals(listOf(second), unpaid.map { it.id })
        val payment = db.supplierDao().observeLedger(supplier.id).first().last()
        assertEquals(-7_000L, payment.amountPaise)
        assertEquals("PURCHASE", payment.refType)
        assertEquals(first, payment.refId)
        assertEquals(9_000L, db.supplierDao().balance(supplier.id))
    }

    @Test fun supplierPaymentCannotExceedTheExplicitlySelectedPurchaseBalance() = runBlocking {
        val selected = buy(PurchaseItem(product.id, 1_000, 500))
        buy(PurchaseItem(product.id, 1_000, 900))

        try {
            paySupplier(supplier.id, 600, PaymentMethod.CASH, purchaseId = selected)
            fail("expected SupplierPaymentException")
        } catch (e: SupplierPaymentException) {
            assertEquals(SupplierPaymentException.Reason.EXCEEDS_PURCHASE_BALANCE, e.reason)
        }
        assertEquals(0L, db.purchaseDao().getById(selected)!!.paidPaise)
        assertEquals(1_400L, db.supplierDao().balance(supplier.id))
        assertEquals(0, db.supplierDao().observeLedger(supplier.id).first().count { it.type == SupplierLedgerType.PAYMENT })
    }

    @Test fun supplierPaymentCannotBeAllocatedToAnotherSuppliersPurchase() = runBlocking {
        val otherSupplier = SupplierEntity(name = "Other supplier")
        db.supplierDao().insert(otherSupplier)
        val otherPurchase = buy(PurchaseItem(product.id, 1_000, 500), supplierId = otherSupplier.id)
        db.supplierDao().insertLedger(
            SupplierLedgerEntity(supplierId = supplier.id, type = SupplierLedgerType.ADJUSTMENT, amountPaise = 1_000),
        )

        try {
            paySupplier(supplier.id, 100, PaymentMethod.CASH, purchaseId = otherPurchase)
            fail("expected SupplierPaymentException")
        } catch (e: SupplierPaymentException) {
            assertEquals(SupplierPaymentException.Reason.PURCHASE_SUPPLIER_MISMATCH, e.reason)
        }
        assertEquals(0L, db.purchaseDao().getById(otherPurchase)!!.paidPaise)
        assertEquals(1_000L, db.supplierDao().balance(supplier.id))
        assertEquals(500L, db.supplierDao().balance(otherSupplier.id))
    }

    @Test fun purchasedStockCanBeSoldEarliestExpiryFirst() = runBlocking {
        buy(PurchaseItem(tracked.id, 3_000, 400, "LATE", LocalDate.of(2027, 6, 1)), PurchaseItem(tracked.id, 3_000, 400, "EARLY", LocalDate.of(2027, 1, 1)))
        val sell = CompleteSaleUseCase(db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao())
        sell(listOf(CartItem(tracked.id, 4_000, 600)), listOf(PaymentInput(PaymentMethod.CASH, 2_400)), today = LocalDate.of(2026, 10, 24))
        val left = db.batchDao().observeForProduct(tracked.id).first().associate { it.batchNo to it.qtyOnHandMilli }
        assertEquals(mapOf<String?, Long>("LATE" to 2_000L), left)       // EARLY used up, 1 piece taken from LATE
    }

    @Test fun serialTrackedPurchaseRequiresOneUniqueSerialPerUnit() = runBlocking {
        val device = ProductEntity(name = "Phone", unitId = product.unitId, sellPricePaise = 20_000, serialTracked = true)
        db.productDao().insert(device)
        val id = buy(PurchaseItem(device.id, 2_000, 15_000, serialNumbers = listOf("IMEI-1", "IMEI-2")))
        val batch = db.batchDao().observeForProduct(device.id).first().single()
        assertEquals(2, db.serialNumberDao().availableForBatch(device.id, batch.id).size)

        try {
            buy(PurchaseItem(device.id, 2_000, 15_000, serialNumbers = listOf("IMEI-3")))
            fail("expected PurchaseException")
        } catch (e: PurchaseException) {
            assertEquals(PurchaseException.Reason.SERIAL_COUNT, e.reason)
        }
        assertEquals(2_000L, db.stockMovementDao().ledgerTotal(device.id))
    }

    @Test fun purchaseRowsAreScopedToTheirBranch() = runBlocking {
        val mainId = buy(PurchaseItem(product.id, 1_000, 500))
        val otherId = "other-branch-purchase"
        db.purchaseDao().insert(
            PurchaseEntity(
                id = otherId,
                branchId = "branch-2",
                supplierId = supplier.id,
                invoiceNo = "OTHER",
                purchaseDate = dayMillis + 1,
                totalPaise = 900,
                paidPaise = 0,
            ),
        )

        val mainRows = db.purchaseDao().observeRecent(branchId = "default").first()
        val otherRows = db.purchaseDao().observeRecent(branchId = "branch-2").first()

        assertEquals(listOf(mainId), mainRows.map { it.id })
        assertEquals(listOf(otherId), otherRows.map { it.id })
        assertNotNull(db.purchaseDao().observeById(mainId, "default").first())
        assertNull(db.purchaseDao().observeById(mainId, "branch-2").first())
        assertTrue(db.purchaseDao().observeLines(mainId, "branch-2").first().isEmpty())
    }

    @Test fun purchaseIsRejectedWhenNoBranchExists() = runBlocking {
        db.branchDao().softDelete("default", dayMillis)
        db.branchDao().softDelete("branch-2", dayMillis)

        try {
            buy(PurchaseItem(product.id, 1_000, 500))
            fail("expected PurchaseException")
        } catch (e: PurchaseException) {
            assertEquals(PurchaseException.Reason.BRANCH_REQUIRED, e.reason)
        }
        assertEquals(0L, db.stockMovementDao().ledgerTotal(product.id))
    }

    @Test fun purchaseRejectsAMissingSupplierWithoutRecordingStock() = runBlocking {
        try {
            buy(PurchaseItem(product.id, 1_000, 500), supplierId = "missing-supplier", paid = 500)
            fail("expected PurchaseException")
        } catch (e: PurchaseException) {
            assertEquals(PurchaseException.Reason.SUPPLIER_NOT_FOUND, e.reason)
        }
        assertEquals(0L, db.stockMovementDao().ledgerTotal(product.id))
    }
}
