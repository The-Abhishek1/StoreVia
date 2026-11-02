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
class CancelSaleUseCaseTest {
    private lateinit var db: StorviaDatabase
    private lateinit var sell: CompleteSaleUseCase
    private lateinit var cancel: CancelSaleUseCase
    private lateinit var giveBack: ReturnItemsUseCase
    private lateinit var product: ProductEntity
    private lateinit var batch: String
    private val today = LocalDate.of(2026, 10, 20)

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        sell = CompleteSaleUseCase(db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao())
        giveBack = ReturnItemsUseCase(db, db.saleDao(), db.batchDao(), db.stockMovementDao(), db.customerDao())
        cancel = CancelSaleUseCase(db, db.saleDao(), giveBack)
        db.storeDao().upsert(StoreEntity(name = "Test"))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        product = ProductEntity(name = "Biscuits", unitId = unit.id, sellPricePaise = 1000, gstRateBp = 1800, taxInclusive = true)
        db.productDao().insert(product)
        batch = ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao()).invoke(product.id, 5_000, 700, "A", today.plusDays(30))
        Unit
    }

    @After fun tearDown() = db.close()

    private suspend fun sale(pcs: Long, payments: List<PaymentInput>, customer: String? = null) =
        sell(listOf(CartItem(product.id, pcs * 1000, 1000)), payments, customerId = customer, today = today)

    @Test fun cancelRestoresStockReversesMoneyAndMarksTheBill() = runBlocking {
        val id = sale(2, listOf(PaymentInput(PaymentMethod.CASH, 2_000)))
        cancel(id, "wrong item")

        assertEquals(5_000L, db.batchDao().getById(batch)!!.qtyOnHandMilli)
        assertEquals(5_000L, db.stockMovementDao().ledgerTotal(product.id))
        val s = db.saleDao().getById(id)!!
        assertEquals(SaleStatus.CANCELLED, s.status); assertEquals("wrong item", s.cancelReason)
        assertEquals(0L, db.saleDao().getPayments(id).sumOf { it.amountPaise })      // paid 2000, refunded 2000
        assertEquals(0L, s.totalPaise - s.returnedPaise - s.paidPaise)               // nothing due
    }

    @Test fun cancelledBillsDisappearFromReports() = runBlocking {
        val id = sale(2, listOf(PaymentInput(PaymentMethod.CASH, 2_000)))
        cancel(id, null)
        val dao = db.reportDao()
        assertEquals(0, dao.sales(0, Long.MAX_VALUE).first().bills)
        assertEquals(0L, dao.sales(0, Long.MAX_VALUE).first().grossPaise)
        assertEquals(0L, dao.returnsTotal(0, Long.MAX_VALUE).first())     // the cancel's credit note isn't counted twice
        assertEquals(0L, dao.lines(0, Long.MAX_VALUE).first().taxablePaise)
    }

    @Test fun cancellingACreditSaleClearsKhataAndRefundsOnlyWhatWasPaid() = runBlocking {
        val c = CustomerEntity(name = "Ravi"); db.customerDao().insert(c)
        val id = sale(3, listOf(PaymentInput(PaymentMethod.CASH, 1_000)), c.id)     // ₹30 bill, ₹10 paid, ₹20 on khata
        assertEquals(2_000L, db.customerDao().balance(c.id))
        cancel(id, null)
        assertEquals(0L, db.customerDao().balance(c.id))
        assertEquals(0L, db.saleDao().getPayments(id).sumOf { it.amountPaise })      // the ₹10 went back
    }

    @Test fun refundGoesBackByTheMethodTheCustomerPaidWith() = runBlocking {
        val id = sale(2, listOf(PaymentInput(PaymentMethod.CASH, 500), PaymentInput(PaymentMethod.UPI, 1_500)))
        cancel(id, null)
        val refund = db.saleDao().getPayments(id).single { it.amountPaise < 0 }
        assertEquals(PaymentMethod.UPI, refund.method)       // the larger payment
        assertEquals(-2_000L, refund.amountPaise)
    }

    @Test fun cannotCancelTwiceOrAfterAReturn() = runBlocking {
        val a = sale(2, listOf(PaymentInput(PaymentMethod.CASH, 2_000)))
        cancel(a, null)
        try { cancel(a, null); fail() } catch (e: CancelSaleException) { assertEquals(CancelSaleException.Reason.ALREADY_CANCELLED, e.reason) }

        val b = sale(2, listOf(PaymentInput(PaymentMethod.CASH, 2_000)))
        giveBack(b, listOf(ReturnItem(product.id, 1_000)))
        try { cancel(b, null); fail() } catch (e: CancelSaleException) { assertEquals(CancelSaleException.Reason.HAS_RETURNS, e.reason) }
    }
}
