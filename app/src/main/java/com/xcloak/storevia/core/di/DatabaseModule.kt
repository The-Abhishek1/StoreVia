package com.xcloak.storevia.core.di

import android.content.Context
import androidx.room.Room
import com.xcloak.storevia.core.database.StorviaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): StorviaDatabase =
        Room.databaseBuilder(ctx, StorviaDatabase::class.java, "storevia.db")
            .addMigrations(
                StorviaDatabase.MIGRATION_1_2,
                StorviaDatabase.MIGRATION_2_3,
                StorviaDatabase.MIGRATION_3_4,
                StorviaDatabase.MIGRATION_4_5,
                StorviaDatabase.MIGRATION_5_6,
                StorviaDatabase.MIGRATION_6_7,
                StorviaDatabase.MIGRATION_7_8,
                StorviaDatabase.MIGRATION_8_9,
            )
            .build()

    @Provides fun storeDao(db: StorviaDatabase) = db.storeDao()
    @Provides fun branchDao(db: StorviaDatabase) = db.branchDao()
    @Provides fun unitDao(db: StorviaDatabase) = db.unitDao()
    @Provides fun productDao(db: StorviaDatabase) = db.productDao()
    @Provides fun recipeDao(db: StorviaDatabase) = db.recipeDao()
    @Provides fun serialNumberDao(db: StorviaDatabase) = db.serialNumberDao()
    @Provides fun batchDao(db: StorviaDatabase) = db.batchDao()
    @Provides fun stockDao(db: StorviaDatabase) = db.stockMovementDao()
    @Provides fun customerDao(db: StorviaDatabase) = db.customerDao()
    @Provides fun saleDao(db: StorviaDatabase) = db.saleDao()
    @Provides fun reportDao(db: StorviaDatabase) = db.reportDao()
    @Provides fun exportDao(db: StorviaDatabase) = db.exportDao()
    @Provides fun categoryDao(db: StorviaDatabase) = db.categoryDao()
    @Provides fun heldCartDao(db: StorviaDatabase) = db.heldCartDao()
    @Provides fun supplierDao(db: StorviaDatabase) = db.supplierDao()
    @Provides fun purchaseDao(db: StorviaDatabase) = db.purchaseDao()
    @Provides fun staffDao(db: StorviaDatabase) = db.staffDao()
    @Provides fun auditDao(db: StorviaDatabase) = db.auditDao()
    @Provides fun syncStateDao(db: StorviaDatabase) = db.syncStateDao()
}
