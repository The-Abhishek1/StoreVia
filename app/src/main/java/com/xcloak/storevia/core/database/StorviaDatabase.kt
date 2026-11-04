package com.xcloak.storevia.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xcloak.storevia.core.database.dao.*
import com.xcloak.storevia.core.database.entity.*

@Database(
    entities = [
        StoreEntity::class, BranchEntity::class, UnitEntity::class, CategoryEntity::class, ProductEntity::class,
        ProductBarcodeEntity::class, BatchEntity::class, StockMovementEntity::class,
        RecipeComponentEntity::class, SerialNumberEntity::class,
        CustomerEntity::class, SaleEntity::class, SaleLineEntity::class, PaymentEntity::class,
        CustomerLedgerEntity::class, SaleReturnEntity::class, SaleReturnLineEntity::class, HeldCartEntity::class,
        SupplierEntity::class, PurchaseEntity::class, PurchaseLineEntity::class, SupplierLedgerEntity::class,
        StaffEntity::class, AuditEntity::class, SyncStateEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StorviaDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No structural schema update is needed for the current 1->2 transition;
                // this keeps existing data usable while matching the new schema hash.
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `store` ADD COLUMN `ownerName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `store` ADD COLUMN `city` TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `attributesJson` TEXT NOT NULL DEFAULT '{}'")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `parentProductId` TEXT")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `variantLabel` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_parentProductId` ON `products` (`parentProductId`)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `recipe_components` (
                        `id` TEXT NOT NULL,
                        `recipeProductId` TEXT NOT NULL,
                        `ingredientProductId` TEXT NOT NULL,
                        `quantityPerUnitMilli` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        `dirty` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`recipeProductId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`ingredientProductId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recipe_components_recipeProductId_ingredientProductId` ON `recipe_components` (`recipeProductId`, `ingredientProductId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recipe_components_ingredientProductId` ON `recipe_components` (`ingredientProductId`)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `serialTracked` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `serial_numbers` (
                        `id` TEXT NOT NULL,
                        `productId` TEXT NOT NULL,
                        `batchId` TEXT NOT NULL,
                        `serialNumber` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `saleLineId` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        `dirty` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`batchId`) REFERENCES `batches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_serial_numbers_serialNumber` ON `serial_numbers` (`serialNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_serial_numbers_productId` ON `serial_numbers` (`productId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_serial_numbers_batchId_status` ON `serial_numbers` (`batchId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_serial_numbers_saleLineId_status` ON `serial_numbers` (`saleLineId`, `status`)")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `branches` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `address` TEXT NOT NULL,
                            `city` TEXT NOT NULL,
                            `phone` TEXT NOT NULL,
                            `isActive` INTEGER NOT NULL,
                            `selectedOnDevice` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `deletedAt` INTEGER,
                            `dirty` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_branches_name` ON `branches` (`name`)")
                db.execSQL("INSERT INTO `branches` (`id`, `name`, `address`, `city`, `phone`, `isActive`, `selectedOnDevice`, `createdAt`, `updatedAt`, `deletedAt`, `dirty`) VALUES (COALESCE((SELECT `id` FROM `store` LIMIT 1), 'default'), COALESCE((SELECT `name` FROM `store` LIMIT 1), 'My Store'), '', '', '', 1, 1, 0, 0, NULL, 1)")
                db.execSQL("ALTER TABLE `batches` ADD COLUMN `branchId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE `stock_movements` ADD COLUMN `branchId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE `sales` ADD COLUMN `branchId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE `purchases` ADD COLUMN `branchId` TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("UPDATE `batches` SET `branchId` = COALESCE((SELECT `id` FROM `store` LIMIT 1), 'default')")
                db.execSQL("UPDATE `stock_movements` SET `branchId` = COALESCE((SELECT `id` FROM `store` LIMIT 1), 'default')")
                db.execSQL("UPDATE `sales` SET `branchId` = COALESCE((SELECT `id` FROM `store` LIMIT 1), 'default')")
                db.execSQL("UPDATE `purchases` SET `branchId` = COALESCE((SELECT `id` FROM `store` LIMIT 1), 'default')")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_batches_branchId_productId_expiryDate` ON `batches` (`branchId`, `productId`, `expiryDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_movements_branchId_productId_createdAt` ON `stock_movements` (`branchId`, `productId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sales_branchId_createdAt` ON `sales` (`branchId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_purchases_branchId_supplierId` ON `purchases` (`branchId`, `supplierId`)")
            }
        }
    }

    abstract fun storeDao(): StoreDao
    abstract fun branchDao(): BranchDao
    abstract fun unitDao(): UnitDao
    abstract fun productDao(): ProductDao
    abstract fun recipeDao(): RecipeDao
    abstract fun serialNumberDao(): SerialNumberDao
    abstract fun batchDao(): BatchDao
    abstract fun stockMovementDao(): StockMovementDao
    abstract fun customerDao(): CustomerDao
    abstract fun saleDao(): SaleDao
    abstract fun reportDao(): ReportDao
    abstract fun exportDao(): ExportDao
    abstract fun categoryDao(): CategoryDao
    abstract fun heldCartDao(): HeldCartDao
    abstract fun supplierDao(): SupplierDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun staffDao(): StaffDao
    abstract fun auditDao(): AuditDao
    abstract fun syncStateDao(): SyncStateDao
}
