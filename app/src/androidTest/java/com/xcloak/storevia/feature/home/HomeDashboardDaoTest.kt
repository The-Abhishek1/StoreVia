package com.xcloak.storevia.feature.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeDashboardDaoTest {
    private lateinit var db: StorviaDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StorviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun stockedProductCountUsesOnlySelectedBranchAndOnHandProducts() = runBlocking {
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        val mainProduct = ProductEntity(name = "Main stock", unitId = unit.id, sellPricePaise = 100)
        val otherProduct = ProductEntity(name = "Other stock", unitId = unit.id, sellPricePaise = 100)
        val noStockProduct = ProductEntity(name = "No stock", unitId = unit.id, sellPricePaise = 100)
        db.productDao().insert(mainProduct)
        db.productDao().insert(otherProduct)
        db.productDao().insert(noStockProduct)
        db.batchDao().insert(
            BatchEntity(productId = mainProduct.id, branchId = "main", qtyReceivedMilli = 1_000, qtyOnHandMilli = 1_000),
        )
        db.batchDao().insert(
            BatchEntity(productId = mainProduct.id, branchId = "main", qtyReceivedMilli = 1_000, qtyOnHandMilli = 500),
        )
        db.batchDao().insert(
            BatchEntity(productId = otherProduct.id, branchId = "other", qtyReceivedMilli = 1_000, qtyOnHandMilli = 1_000),
        )

        assertEquals(1, db.productDao().observeStockedCount("main").first())
        assertEquals(1, db.productDao().observeStockedCount("other").first())
        assertEquals(0, db.productDao().observeStockedCount("empty").first())
    }

    @Test
    fun branchSalesDueExcludesOtherBranchesAndCancelledSales() = runBlocking {
        val customer = CustomerEntity(id = "customer", name = "Customer")
        db.customerDao().insert(customer)
        db.customerDao().insertLedger(
            CustomerLedgerEntity(customerId = customer.id, type = LedgerType.ADJUSTMENT, amountPaise = 900),
        )
        db.saleDao().insertSale(
            SaleEntity(
                id = "main-sale", invoiceNo = "MAIN", branchId = "main", customerId = customer.id,
                subtotalPaise = 1_000, discountPaise = 0, taxPaise = 0, roundOffPaise = 0,
                totalPaise = 1_000, paidPaise = 200, returnedPaise = 100,
            ),
        )
        db.saleDao().insertSale(
            SaleEntity(
                id = "other-sale", invoiceNo = "OTHER", branchId = "other", customerId = customer.id,
                subtotalPaise = 800, discountPaise = 0, taxPaise = 0, roundOffPaise = 0,
                totalPaise = 800, paidPaise = 300,
            ),
        )
        db.saleDao().insertSale(
            SaleEntity(
                id = "cancelled-sale", invoiceNo = "CANCELLED", branchId = "main", customerId = customer.id,
                status = SaleStatus.CANCELLED, subtotalPaise = 400, discountPaise = 0, taxPaise = 0,
                roundOffPaise = 0, totalPaise = 400, paidPaise = 0,
            ),
        )

        assertEquals(700L, db.customerDao().observeBranchSalesDue("main").first())
        assertEquals(500L, db.customerDao().observeBranchSalesDue("other").first())
        assertEquals(0L, db.customerDao().observeBranchSalesDue("empty").first())
        assertEquals(900L, db.customerDao().observeTotalDue().first())
    }
}
