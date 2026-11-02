package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.domain.sale.ReceiveStockUseCase
import com.xcloak.storevia.domain.stock.AdjustStockUseCase
import com.xcloak.storevia.domain.stock.StockAdjustException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AdjustStockUseCaseTest {
    private lateinit var db: StorviaDatabase
    private lateinit var adjust: AdjustStockUseCase
    private lateinit var productId: String
    private lateinit var batchId: String

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        adjust = AdjustStockUseCase(db, db.batchDao(), db.stockMovementDao())
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        val p = ProductEntity(name = "Milk", unitId = unit.id, sellPricePaise = 500)
        db.productDao().insert(p)
        productId = p.id
        batchId = ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao()).invoke(p.id, 10_000, 400, "M1", LocalDate.now().plusDays(5))
        Unit
    }

    @After fun tearDown() = db.close()

    @Test fun damageReducesBatchAndWritesLedger() = runBlocking {
        adjust(batchId, MovementType.DAMAGE, 2_000, "dropped crate")
        assertEquals(8_000L, db.batchDao().getById(batchId)!!.qtyOnHandMilli)
        assertEquals(8_000L, db.stockMovementDao().ledgerTotal(productId))
    }

    @Test fun countCorrectionCanAddStock() = runBlocking {
        adjust(batchId, MovementType.ADJUSTMENT_IN, 1_000)
        assertEquals(11_000L, db.batchDao().getById(batchId)!!.qtyOnHandMilli)
        assertEquals(11_000L, db.stockMovementDao().ledgerTotal(productId))
    }

    @Test fun cannotRemoveMoreThanOnHand() = runBlocking {
        try {
            adjust(batchId, MovementType.EXPIRED, 10_001)
            fail("expected StockAdjustException")
        } catch (e: StockAdjustException) {
            assertEquals(StockAdjustException.Reason.EXCEEDS_ON_HAND, e.reason)
        }
        assertEquals(10_000L, db.batchDao().getById(batchId)!!.qtyOnHandMilli)
    }

    @Test fun saleMovementTypeIsRejected() = runBlocking {
        try {
            adjust(batchId, MovementType.SALE, 1_000)
            fail("expected StockAdjustException")
        } catch (e: StockAdjustException) {
            assertEquals(StockAdjustException.Reason.INVALID_TYPE, e.reason)
        }
    }
}
