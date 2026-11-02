package com.xcloak.storevia.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.CategoryEntity
import com.xcloak.storevia.core.database.entity.ProductEntity
import com.xcloak.storevia.core.database.entity.UnitEntity
import com.xcloak.storevia.data.product.ProductRepository
import com.xcloak.storevia.data.product.SaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductRepositoryTest {
    private lateinit var db: StorviaDatabase
    private lateinit var repo: ProductRepository
    private lateinit var unit: UnitEntity

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StorviaDatabase::class.java).allowMainThreadQueries().build()
        repo = ProductRepository(db, db.productDao())
        unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
    }

    @After fun tearDown() = db.close()

    private fun product(name: String) = ProductEntity(name = name, unitId = unit.id, sellPricePaise = 1000)

    @Test fun duplicateBarcodeIsRejectedWithOwnerName() = runBlocking {
        repo.save(product("Tea"), "8901234567890", isNew = true)
        val r = repo.save(product("Coffee"), "8901234567890", isNew = true)
        assertEquals(SaveResult.DuplicateBarcode("Tea"), r)
    }

    @Test fun editingKeepsOwnBarcode() = runBlocking {
        val tea = product("Tea")
        repo.save(tea, "111", isNew = true)
        val r = repo.save(tea.copy(name = "Green Tea"), "111", isNew = false)
        assertTrue(r is SaveResult.Saved)
        assertEquals("Green Tea", db.productDao().getById(tea.id)!!.name)
    }

    @Test fun archivedProductDisappearsAndFreesBarcode() = runBlocking {
        val tea = product("Tea")
        repo.save(tea, "222", isNew = true)
        repo.archive(tea.id)
        assertTrue(db.productDao().listWithStock("").first().isEmpty())
        assertTrue(repo.save(product("Coffee"), "222", isNew = true) is SaveResult.Saved)
    }

    @Test fun searchFindsByNameAndBarcode() = runBlocking {
        val tea = product("Masala Tea")
        repo.save(tea, "333", isNew = true)
        assertEquals(1, db.productDao().listWithStock("masala").first().size)
        assertEquals(1, db.productDao().listWithStock("333").first().size)
        assertEquals(0, db.productDao().listWithStock("coffee").first().size)
    }

    @Test fun categoryFilterAndDeletionLeaveProductsUncategorised() = runBlocking {
        val dairy = CategoryEntity(name = "Dairy"); db.categoryDao().insert(dairy)
        val milk = product("Milk").copy(categoryId = dairy.id); repo.save(milk, null, isNew = true)
        repo.save(product("Soap"), null, isNew = true)

        assertEquals(listOf("Milk"), db.productDao().listWithStockByCategory("", dairy.id).first().map { it.name })
        assertEquals(1, db.categoryDao().observeProductCounts().first().single { it.id == dairy.id }.count)

        db.categoryDao().clearFromProducts(dairy.id, 1L); db.categoryDao().softDelete(dairy.id, 1L)
        assertTrue(db.categoryDao().observeAll().first().isEmpty())
        assertEquals(2, db.productDao().listWithStock("").first().size)              // both products survive
        assertEquals(null, db.productDao().getById(milk.id)!!.categoryId)
    }
}
