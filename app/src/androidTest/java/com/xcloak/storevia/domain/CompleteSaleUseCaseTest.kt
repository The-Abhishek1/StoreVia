package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.domain.sale.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Product used throughout: ₹10.00 per piece, GST 18% inclusive. Quantities are thousandths (1000 = 1 pc). */
@RunWith(AndroidJUnit4::class)
class CompleteSaleUseCaseTest {
    private lateinit var db: StorviaDatabase
    private lateinit var sell: CompleteSaleUseCase
    private lateinit var receive: ReceiveStockUseCase
    private val today = LocalDate.of(2026, 10, 20)

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        sell = CompleteSaleUseCase(
            db, db.storeDao(), db.productDao(), db.batchDao(),
            db.stockMovementDao(), db.saleDao(), db.customerDao(),
        )
        receive = ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao())
    }

    @After fun tearDown() = db.close()

    private suspend fun seed(allowNegative: Boolean = true): ProductEntity {
        db.storeDao().upsert(StoreEntity(name = "Test", allowNegativeStock = allowNegative))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        val p = ProductEntity(
            name = "Biscuits", unitId = unit.id, sellPricePaise = 1000,
            costPaise = 700, gstRateBp = 1800, taxInclusive = true,
        )
        db.productDao().insert(p)
        return p
    }

    private fun cash(paise: Long) = listOf(PaymentInput(PaymentMethod.CASH, paise))

    @Test fun sellsFromEarliestExpiryFirst() = runBlocking {
        val p = seed()
        receive(p.id, 5_000, 700, "LATE", today.plusDays(90))
        receive(p.id, 5_000, 700, "EARLY", today.plusDays(10))
        sell(listOf(CartItem(p.id, 7_000, 1000)), cash(7_000), today = today)

        val remaining = db.batchDao().fefoBatches(p.id, today)
        assertEquals(1, remaining.size)                  // EARLY fully used up
        assertEquals("LATE", remaining[0].batchNo)
        assertEquals(3_000L, remaining[0].qtyOnHandMilli)
    }

    @Test fun ledgerAlwaysMatchesBatchBalances() = runBlocking {
        val p = seed()
        receive(p.id, 10_000, 700, "A", today.plusDays(30))
        receive(p.id, 4_000, 700, "B", today.plusDays(60))
        sell(listOf(CartItem(p.id, 12_000, 1000)), cash(12_000), today = today)

        val onHand = db.batchDao().fefoBatches(p.id, today).sumOf { it.qtyOnHandMilli }
        assertEquals(2_000L, onHand)
        assertEquals(onHand, db.stockMovementDao().ledgerTotal(p.id))
    }

    @Test fun expiredBatchIsNeverSold() = runBlocking {
        val p = seed(allowNegative = false)
        receive(p.id, 5_000, 700, "OLD", today.minusDays(1))
        try {
            sell(listOf(CartItem(p.id, 1_000, 1000)), cash(1_000), today = today)
            fail("expected InsufficientStockException")
        } catch (_: InsufficientStockException) { }
    }

    @Test fun failedSaleRollsBackEverything() = runBlocking {
        val p = seed(allowNegative = false)
        val batchId = receive(p.id, 5_000, 700, "A", today.plusDays(30))
        val empty = ProductEntity(name = "Empty", unitId = p.unitId, sellPricePaise = 500)
        db.productDao().insert(empty)
        try {
            // Second item has no stock: the first item's deduction must be undone too.
            sell(listOf(CartItem(p.id, 1_000, 1000), CartItem(empty.id, 1_000, 500)), cash(1_500), today = today)
            fail("expected InsufficientStockException")
        } catch (_: InsufficientStockException) { }

        val batch = db.batchDao().fefoBatches(p.id, today).single { it.id == batchId }
        assertEquals(5_000L, batch.qtyOnHandMilli)
        assertEquals(1L, db.storeDao().get()!!.nextInvoiceNo)   // invoice number not consumed
        assertEquals(5_000L, db.stockMovementDao().ledgerTotal(p.id))
    }

    @Test fun partialPaymentCreatesKhataEntry() = runBlocking {
        val p = seed()
        receive(p.id, 5_000, 700, "A", today.plusDays(30))
        val c = CustomerEntity(name = "Ravi"); db.customerDao().insert(c)
        // Bill = 2 pcs x ₹10 = ₹20. Paid ₹10 -> ₹10 goes on khata.
        sell(listOf(CartItem(p.id, 2_000, 1000)), cash(1_000), customerId = c.id, today = today)
        assertEquals(1_000L, db.customerDao().observeBalance(c.id).first())
    }

    @Test fun creditSaleRequiresCustomer() = runBlocking {
        val p = seed()
        receive(p.id, 5_000, 700, "A", today.plusDays(30))
        try {
            sell(listOf(CartItem(p.id, 2_000, 1000)), cash(1_000), today = today)
            fail("expected SaleValidationException")
        } catch (_: SaleValidationException) { }
    }

    @Test fun previewMatchesCompletedSale() = runBlocking {
        val p = seed()
        receive(p.id, 4_000, 700, "A", today.plusDays(30))
        receive(p.id, 4_000, 700, "B", today.plusDays(60))
        val items = listOf(CartItem(p.id, 6_000, 1000, discountPaise = 333))
        val preview = sell.preview(items, today = today)
        val id = sell(items, cash(preview.totals.totalPaise), today = today)
        val saved = db.saleDao().observeById(id).first()!!
        assertEquals(preview.totals.totalPaise, saved.totalPaise)
        assertEquals(preview.totals.taxPaise, saved.taxPaise)
        assertEquals(preview.totals.roundOffPaise, saved.roundOffPaise)
        // preview must not touch stock
        assertEquals(8_000L - 6_000L, db.batchDao().fefoBatches(p.id, today).sumOf { it.qtyOnHandMilli })
    }

    @Test fun billDiscountIsSpreadOverLinesAndReconciles() = runBlocking {
        val a = seed()                                            // ₹10, 18% inclusive
        val b = ProductEntity(name = "Pencil", unitId = a.unitId, sellPricePaise = 500)   // ₹5, no GST
        db.productDao().insert(b)
        receive(a.id, 5_000, 700, "A", today.plusDays(30)); receive(b.id, 5_000, 300, "B", today.plusDays(30))

        val items = listOf(CartItem(a.id, 3_000, 1000), CartItem(b.id, 2_000, 500))    // ₹30 + ₹10 = ₹40
        val preview = sell.preview(items, today = today, billDiscountPaise = 400)
        assertEquals(3_600L, preview.totals.totalPaise)
        assertEquals(400L, preview.totals.discountPaise)

        val id = sell(items, cash(3_600), today = today, billDiscountPaise = 400)
        val saved = db.saleDao().observeById(id).first()!!
        assertEquals(preview.totals.totalPaise, saved.totalPaise)
        assertEquals(400L, saved.discountPaise)
        val lines = db.saleDao().getLines(id)
        assertEquals(300L, lines.single { it.productId == a.id }.discountPaise)       // 400 * 3000/4000
        assertEquals(100L, lines.single { it.productId == b.id }.discountPaise)
    }

    @Test fun discountBiggerThanTheBillIsRejected() = runBlocking {
        val p = seed(); receive(p.id, 5_000, 700, "A", today.plusDays(30))
        try {
            sell(listOf(CartItem(p.id, 1_000, 1000)), cash(0), today = today, billDiscountPaise = 1_001)
            fail("expected SaleValidationException")
        } catch (e: SaleValidationException) { /* ok */ }
    }

    @Test fun splitPaymentsAreRecordedPerMethod() = runBlocking {
        val p = seed(); receive(p.id, 5_000, 700, "A", today.plusDays(30))
        val id = sell(listOf(CartItem(p.id, 2_000, 1000)),
            listOf(PaymentInput(PaymentMethod.CASH, 500), PaymentInput(PaymentMethod.UPI, 1_500)), today = today)
        val pays = db.saleDao().observePayments(id).first()
        assertEquals(2, pays.size)
        assertEquals(500L, pays.single { it.method == PaymentMethod.CASH }.amountPaise)
        assertEquals(1_500L, pays.single { it.method == PaymentMethod.UPI }.amountPaise)
        assertEquals(2_000L, db.saleDao().getById(id)!!.paidPaise)
    }
}
