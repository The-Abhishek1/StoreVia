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

    @Test fun version7To8AddsBranchesAndAssignsExistingRowsToDefaultBranch() {
        val name = "migration-7-8-test"
        helper.createDatabase(name, 7).use { db ->
            db.execSQL(
                """INSERT INTO sales (
                    id, invoiceNo, status, interState, subtotalPaise, discountPaise, taxPaise, roundOffPaise,
                    totalPaise, paidPaise, returnedPaise, createdAt, dirty
                ) VALUES ('sale-1', 'INV-1', 'COMPLETED', 0, 1000, 0, 0, 0, 1000, 1000, 0, 1, 1)""",
            )
        }

        helper.runMigrationsAndValidate(name, 8, true, StorviaDatabase.MIGRATION_7_8).use { db ->
            db.query("SELECT id, selectedOnDevice FROM branches WHERE id = 'default'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(1) == 1)
            }
            db.query("SELECT branchId FROM sales WHERE id = 'sale-1'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "default")
            }
            db.query("SELECT branchId FROM batches LIMIT 1").use { cursor ->
                check(cursor.getColumnIndex("branchId") >= 0)
            }
        }
    }

    @Test fun version8To9MakesExistingHeldCartsSyncableWithoutDroppingThem() {
        val name = "migration-8-9-test"
        helper.createDatabase(name, 8).use { db ->
            db.execSQL(
                """INSERT INTO held_carts (
                    id, label, customerId, interState, billDiscountPaise, itemsJson, itemCount, createdAt
                ) VALUES ('held-1', 'Ravi', NULL, 0, 0, '[]', 0, 1234)""",
            )
        }

        helper.runMigrationsAndValidate(name, 9, true, StorviaDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT createdAt, updatedAt, deletedAt, dirty FROM held_carts WHERE id = 'held-1'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getLong(0) == 1234L)
                check(cursor.getLong(1) == 1234L)
                check(cursor.isNull(2))
                check(cursor.getInt(3) == 1)
            }
        }
    }
}
