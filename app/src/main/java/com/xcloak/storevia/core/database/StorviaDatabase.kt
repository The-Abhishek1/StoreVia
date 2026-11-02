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
        StoreEntity::class, UnitEntity::class, CategoryEntity::class, ProductEntity::class,
        ProductBarcodeEntity::class, BatchEntity::class, StockMovementEntity::class,
        CustomerEntity::class, SaleEntity::class, SaleLineEntity::class, PaymentEntity::class,
        CustomerLedgerEntity::class, SaleReturnEntity::class, SaleReturnLineEntity::class, HeldCartEntity::class,
        SupplierEntity::class, PurchaseEntity::class, PurchaseLineEntity::class, SupplierLedgerEntity::class,
        StaffEntity::class, AuditEntity::class, SyncStateEntity::class,
    ],
    version = 2,
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
    }

    abstract fun storeDao(): StoreDao
    abstract fun unitDao(): UnitDao
    abstract fun productDao(): ProductDao
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
