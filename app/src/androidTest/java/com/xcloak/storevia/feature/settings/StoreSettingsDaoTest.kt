package com.xcloak.storevia.feature.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.StoreEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreSettingsDaoTest {
    private lateinit var db: StorviaDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StorviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    @Test fun staleInvoiceNumberDoesNotMoveCounterBackwardsWhileSavingSettings() = runBlocking {
        val store = StoreEntity(id = "store", name = "Original", nextInvoiceNo = 4)
        db.storeDao().upsert(store)
        db.storeDao().incrementInvoiceNo(store.id)

        val update = db.storeDao().updateSettings(
            id = store.id,
            name = "Updated",
            ownerName = store.ownerName,
            address = store.address,
            city = store.city,
            phone = store.phone,
            gstin = store.gstin,
            stateCode = store.stateCode,
            invoicePrefix = store.invoicePrefix,
            nextInvoiceNo = store.nextInvoiceNo,
            allowNegativeStock = store.allowNegativeStock,
            updatedAt = System.currentTimeMillis(),
        )

        assertEquals(1, update)
        assertEquals("Updated", db.storeDao().get()?.name)
        assertEquals(5L, db.storeDao().get()?.nextInvoiceNo)
    }
}
