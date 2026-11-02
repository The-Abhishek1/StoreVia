package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.core.util.Csv
import com.xcloak.storevia.domain.imports.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductImportUseCaseTest {
    private lateinit var db: StorviaDatabase
    private lateinit var import: ProductImportUseCase
    private lateinit var units: List<UnitEntity>

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        import = ProductImportUseCase(db, db.productDao(), db.batchDao(), db.stockMovementDao())
        units = listOf(UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false), UnitEntity(name = "Kilogram", symbol = "kg", allowDecimal = true))
        db.unitDao().insertAll(units)
        Unit
    }

    @After fun tearDown() = db.close()

    private fun rows(csv: String) = ProductCsvParser.parse(Csv.parse(csv), units).rows

    @Test fun createsProductsWithOpeningStockAndLedger() = runBlocking {
        val s = import.import(rows("name,price,barcode,opening_qty,expiry,unit\nTea,55,111,25.5,2027-03-31,kg\nSoap,30,,,,"), DuplicatePolicy.SKIP)
        assertEquals(ImportSummary(2, 0, 0), s)
        val tea = db.productDao().findByBarcode("111")!!
        assertEquals(25_500L, db.batchDao().observeForProduct(tea.id).first().sumOf { it.qtyOnHandMilli })
        assertEquals(25_500L, db.stockMovementDao().ledgerTotal(tea.id))
        assertEquals(true, tea.trackExpiry)
    }

    @Test fun skipLeavesExistingProductsAlone() = runBlocking {
        import.import(rows("name,price,barcode\nTea,55,111"), DuplicatePolicy.SKIP)
        val s = import.import(rows("name,price,barcode\nTea,99,111"), DuplicatePolicy.SKIP)
        assertEquals(ImportSummary(0, 0, 1), s)
        assertEquals(5_500L, db.productDao().findByBarcode("111")!!.sellPricePaise)
    }

    @Test fun updateChangesPriceButNeverStock() = runBlocking {
        import.import(rows("name,price,barcode,opening_qty\nTea,55,111,10"), DuplicatePolicy.SKIP)
        val s = import.import(rows("name,price,barcode,opening_qty\nTea,99,111,500"), DuplicatePolicy.UPDATE)
        assertEquals(ImportSummary(0, 1, 0), s)
        val tea = db.productDao().findByBarcode("111")!!
        assertEquals(9_900L, tea.sellPricePaise)
        assertEquals(10_000L, db.stockMovementDao().ledgerTotal(tea.id))   // still the original 10
    }

    @Test fun previewCountsNewAndExisting() = runBlocking {
        import.import(rows("name,price,sku\nTea,55,T1"), DuplicatePolicy.SKIP)
        val p = import.preview(rows("name,price,sku\nTea again,55,T1\nSugar,40,S1"))
        assertEquals(ImportPreview(newRows = 1, existingRows = 1), p)
    }

    @Test fun nameOnlyRowsMatchByNameIgnoringCase() = runBlocking {
        import.import(rows("name,price\nTea,55"), DuplicatePolicy.SKIP)
        assertEquals(ImportPreview(0, 1), import.preview(rows("name,price\nTEA,60")))
    }
}
