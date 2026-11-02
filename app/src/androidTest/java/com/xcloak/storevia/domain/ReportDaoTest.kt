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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** One product at ₹10 (18% GST inclusive), cost ₹7. Sell 2, return 1. */
@RunWith(AndroidJUnit4::class)
class ReportDaoTest {
    private lateinit var db: StorviaDatabase
    private val from = 0L
    private val to = Long.MAX_VALUE

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        db.storeDao().upsert(StoreEntity(name = "Test"))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        val p = ProductEntity(name = "Biscuits", unitId = unit.id, sellPricePaise = 1000, gstRateBp = 1800, taxInclusive = true)
        db.productDao().insert(p)
        ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao()).invoke(p.id, 5_000, 700, "A", LocalDate.now().plusDays(10))
        val sell = CompleteSaleUseCase(db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao())
        val saleId = sell(listOf(CartItem(p.id, 2_000, 1000)), listOf(PaymentInput(PaymentMethod.CASH, 2_000)))
        ReturnItemsUseCase(db, db.saleDao(), db.batchDao(), db.stockMovementDao(), db.customerDao())
            .invoke(saleId, listOf(ReturnItem(p.id, 1_000)))
        Unit
    }

    @After fun tearDown() = db.close()

    @Test fun salesAndReturnTotals() = runBlocking {
        val dao = db.reportDao()
        val sales = dao.sales(from, to).first()
        assertEquals(2_000L, sales.grossPaise); assertEquals(1, sales.bills)
        assertEquals(1_000L, dao.returnsTotal(from, to).first())
    }

    @Test fun profitInputsMatchHandCalculation() = runBlocking {
        val dao = db.reportDao()
        val lines = dao.lines(from, to).first()
        assertEquals(1_695L, lines.taxablePaise); assertEquals(1_400L, lines.costPaise); assertEquals(305L, lines.taxPaise)
        val ret = dao.returnLines(from, to).first()
        assertEquals(847L, ret.taxablePaise); assertEquals(700L, ret.costPaise)
        // (1695 - 1400) - (847 - 700) = 148
        assertEquals(148L, (lines.taxablePaise - lines.costPaise) - (ret.taxablePaise - ret.costPaise))
    }

    @Test fun paymentsAreNetOfRefunds() = runBlocking {
        val rows = db.reportDao().paymentsByMethod(from, to).first()
        assertEquals(1, rows.size)
        assertEquals(PaymentMethod.CASH, rows[0].method); assertEquals(1_000L, rows[0].totalPaise)
    }

    @Test fun topProductsAndStockValue() = runBlocking {
        val dao = db.reportDao()
        val top = dao.topProducts(from, to, 5).first()
        assertEquals(2_000L, top[0].revenuePaise); assertEquals("pcs", top[0].unitSymbol)
        val v = dao.stockValue().first()          // 5 - 2 + 1 = 4 pieces left
        assertEquals(2_800L, v.costPaise); assertEquals(4_000L, v.retailPaise)
        val e = dao.expiryBetween(LocalDate.now(), LocalDate.now().plusDays(30)).first()
        assertEquals(1, e.batches); assertEquals(2_800L, e.costPaise)
    }
}
