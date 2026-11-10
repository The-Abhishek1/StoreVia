package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.domain.sale.*
import com.xcloak.storevia.domain.stock.AdjustStockUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudSalesInventoryRoutingTest {
    private lateinit var db: StorviaDatabase
    private lateinit var cloud: RecordingCloudOperations

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StorviaDatabase::class.java).allowMainThreadQueries().build()
        cloud = RecordingCloudOperations()
    }

    @After fun tearDown() = db.close()

    @Test fun checkoutAndPreviewUseServerWithoutRoomCatalog() = runBlocking {
        val useCase = CompleteSaleUseCase(
            db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao(),
            cloudOperations = cloud,
        )
        val items = listOf(CartItem("cloud-product", 1_000, 250))
        val preview = useCase.preview(items)
        val saleId = useCase(items, listOf(PaymentInput(PaymentMethod.CASH, 250)))

        assertEquals(250L, preview.totals.totalPaise)
        assertEquals("cloud-sale", saleId)
        assertEquals(1, cloud.checkoutCalls)
        assertEquals(items, cloud.lastItems)
        assertEquals(0, db.saleDao().observeRecent().first().size)
    }

    @Test fun returnCancelAndStockAdjustmentAreAcknowledgedByCloudOperations() = runBlocking {
        val returnUseCase = ReturnItemsUseCase(
            db, db.saleDao(), db.batchDao(), db.stockMovementDao(), db.customerDao(), cloudOperations = cloud,
        )
        assertEquals("cloud-return", returnUseCase("cloud-sale", listOf(ReturnItem("cloud-product", 1_000))))

        val cancel = CancelSaleUseCase(db, db.saleDao(), returnUseCase, cloudOperations = cloud)
        cancel("cloud-sale", "mistake")
        val adjust = AdjustStockUseCase(db, db.batchDao(), db.stockMovementDao(), cloudOperations = cloud)
        adjust("cloud-batch", MovementType.ADJUSTMENT_IN, 1_000)

        assertEquals(1, cloud.returnCalls)
        assertEquals(1, cloud.cancelCalls)
        assertEquals(1, cloud.adjustCalls)
        assertEquals(0, db.stockMovementDao().observeForProduct("cloud-product").first().size)
    }

    private class RecordingCloudOperations : SalesInventoryOperations {
        override fun isCloudMode() = true
        var checkoutCalls = 0
        var returnCalls = 0
        var cancelCalls = 0
        var adjustCalls = 0
        var lastItems: List<CartItem>? = null

        override suspend fun checkout(
            items: List<CartItem>, payments: List<PaymentInput>, customerId: String?,
            interState: Boolean, billDiscountPaise: Long, now: Long,
        ): String {
            checkoutCalls++
            lastItems = items
            return "cloud-sale"
        }

        override suspend fun previewSale(
            items: List<CartItem>, interState: Boolean, billDiscountPaise: Long, now: Long,
        ) = SalePreview(SaleTotals(250, 0, 0, 0, 250), emptyList(), false)

        override suspend fun returnItems(
            saleId: String, items: List<ReturnItem>, refund: RefundMode, now: Long,
        ): String {
            returnCalls++
            return "cloud-return"
        }

        override suspend fun previewReturn(saleId: String, items: List<ReturnItem>) =
            ReturnPreview(0, 0, 0)

        override suspend fun cancelSale(saleId: String, reason: String?, now: Long): String {
            cancelCalls++
            return saleId
        }

        override suspend fun adjustStock(
            batchId: String, type: MovementType, qtyMilli: Long, note: String?, now: Long,
        ): String {
            adjustCalls++
            return batchId
        }
    }
}
