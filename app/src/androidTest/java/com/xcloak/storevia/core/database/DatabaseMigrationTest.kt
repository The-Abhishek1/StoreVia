package com.xcloak.storevia.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StorviaDatabase::class.java,
    )

    @Test fun version6To7AddsSerialInventoryWithoutLosingExistingProducts() {
        val name = "migration-6-7-test"
        helper.createDatabase(name, 6).use { db ->
            db.execSQL(
                """INSERT INTO products (
                    id, name, unitId, gstRateBp, taxInclusive, sellPricePaise, costPaise, trackExpiry,
                    minStockMilli, isActive, attributesJson, createdAt, updatedAt, dirty
                ) VALUES ('product-1', 'Existing', 'unit-1', 0, 1, 1000, 500, 0, 0, 1, '{}', 1, 1, 1)""",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            7,
            true,
            StorviaDatabase.MIGRATION_6_7,
        ).use { db ->
            db.query("SELECT serialTracked FROM products WHERE id = 'product-1'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 0)
            }
            db.query("SELECT COUNT(*) FROM serial_numbers").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 0)
            }
        }
    }
}
