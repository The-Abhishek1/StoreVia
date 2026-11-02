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

/** Product: ₹10 per piece, 18% GST inclusive. 1000 = 1 piece. */
@RunWith(AndroidJUnit4::class)
class ReturnItemsUseCaseTest {
    private lateinit var db: StorviaDatabase
    private lateinit var sell: CompleteSaleUseCase
    private lateinit var receive: ReceiveStockUseCase
    private lateinit var giveBack: ReturnItemsUseCase
    private lateinit var product: ProductEntity
    private lateinit var batchA: String
    private lateinit var batchB: String
    private val today = LocalDate.of(2026, 10, 20)

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        sell = CompleteSaleUseCase(db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao())
        receive = ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao())
        giveBack = ReturnItemsUseCase(db, db.saleDao(), db.batchDao(), db.stockMovementDao(), db.customerDao())
        db.storeDao().upsert(StoreEntity(name = "Test"))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        product = ProductEntity(name = "Biscuits", unitId = unit.id, sellPricePaise = 1000, gstRateBp = 1800, taxInclusive = true)
        db.productDao().insert(product)
        batchA = receive(product.id, 5_000, 700, "A", today.plusDays(10))
        batchB = receive(product.id, 5_000, 700, "B", today.plusDays(60))
        Unit
    }

    @After fun tearDown() = db.close()

    private fun cash(p: Long) = listOf(PaymentInput(PaymentMethod.CASH, p))
    private suspend fun sale(pcs: Long, paid: Long, customer: String? = null) =
        sell(listOf(CartItem(product.id, pcs * 1000, 1000)), if (paid > 0) cash(paid) else emptyList(), customerId = customer, today = today)

    private suspend fun consistent() {
        val sum = db.batchDao().fefoBatches(product.id, today).sumOf { it.qtyOnHandMilli }
        assertEquals(sum, db.stockMovementDao().ledgerTotal(product.id))
    }

    @Test fun fullReturnRestoresOriginalBatchAndRefundsCash() = runBlocking {
        val id = sale(2, 2_000)
        giveBack(id, listOf(ReturnItem(product.id, 2_000)))
        assertEquals(5_000L, db.batchDao().getById(batchA)!!.qtyOnHandMilli)
        val s = db.saleDao().getById(id)!!
        assertEquals(SaleStatus.RETURNED, s.status)
        assertEquals(2_000L, s.returnedPaise)
        assertEquals(0L, s.paidPaise)                       // refunded money is no longer "paid"
        assertEquals(-2_000L, db.saleDao().observePayments(id).first().last().amountPaise)
        consistent()
    }

    @Test fun returnOnCreditOnlyReducesKhata() = runBlocking {
        val c = CustomerEntity(name = "Ravi"); db.customerDao().insert(c)
        val id = sale(3, 1_000, c.id)                        // ₹30 bill, ₹10 paid, ₹20 on khata
        val r = giveBack.preview(id, listOf(ReturnItem(product.id, 1_000)))
        assertEquals(1_000L, r.dueReducedPaise); assertEquals(0L, r.refundPaise)
        giveBack(id, listOf(ReturnItem(product.id, 1_000)))
        assertEquals(1_000L, db.customerDao().balance(c.id))   // 2000 owed -> 1000
        val s = db.saleDao().getById(id)!!
        assertEquals(1_000L, s.totalPaise - s.returnedPaise - s.paidPaise)   // still due on the bill
        assertEquals(SaleStatus.PARTIAL_RETURN, s.status)
        consistent()
    }

    @Test fun refundBeyondDueCanBeKeptAsKhataCredit() = runBlocking {
        val c = CustomerEntity(name = "Ravi"); db.customerDao().insert(c)
        val id = sale(2, 500, c.id)                          // ₹20 bill, ₹5 paid, ₹15 owed
        giveBack(id, listOf(ReturnItem(product.id, 2_000)), RefundMode.KhataCredit)
        assertEquals(-500L, db.customerDao().balance(c.id))   // owed 1500, credit 2000 -> customer has ₹5 advance
        assertEquals(0L, db.saleDao().getById(id)!!.paidPaise)
    }

    @Test fun cannotReturnMoreThanSoldAcrossReturns() = runBlocking {
        val id = sale(2, 2_000)
        giveBack(id, listOf(ReturnItem(product.id, 1_500)))
        try {
            giveBack(id, listOf(ReturnItem(product.id, 1_000)))
            fail("expected ReturnException")
        } catch (e: ReturnException) {
            assertEquals(ReturnException.Reason.EXCEEDS_RETURNABLE, e.reason)
        }
        assertEquals(500L, db.saleDao().getLines(id).sumOf { it.qtyMilli - it.returnedQtyMilli })
    }

    @Test fun partialReturnAcrossBatchesPutsStockBackInOriginalBatch() = runBlocking {
        val id = sale(7, 7_000)                              // 5 from A, 2 from B
        giveBack(id, listOf(ReturnItem(product.id, 3_000)))
        assertEquals(3_000L, db.batchDao().getById(batchA)!!.qtyOnHandMilli)   // 0 + 3 back
        assertEquals(3_000L, db.batchDao().getById(batchB)!!.qtyOnHandMilli)   // untouched
        assertEquals(3_000L, db.saleDao().getById(id)!!.returnedPaise)
        consistent()
    }

    @Test fun creditRefundNeedsACustomer() = runBlocking {
        val id = sale(1, 1_000)
        try {
            giveBack(id, listOf(ReturnItem(product.id, 1_000)), RefundMode.KhataCredit)
            fail("expected ReturnException")
        } catch (e: ReturnException) {
            assertEquals(ReturnException.Reason.CREDIT_NEEDS_CUSTOMER, e.reason)
        }
        assertEquals(0L, db.saleDao().getById(id)!!.returnedPaise)   // rolled back
    }
}
