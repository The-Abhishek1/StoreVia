package com.xcloak.storevia.data.product

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.entity.ProductBarcodeEntity
import com.xcloak.storevia.core.database.entity.ProductEntity
import javax.inject.Inject

sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult
    data class DuplicateBarcode(val productName: String) : SaveResult
    data object InvalidVariantParent : SaveResult
}

class ProductRepository private constructor(
    private val db: StorviaDatabase,
    private val productDao: ProductDao,
    private val catalog: ProductCatalogRepository?,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
    @Suppress("UNUSED_PARAMETER") localTestConstruction: Boolean,
) {
    @Inject constructor(
        db: StorviaDatabase,
        productDao: ProductDao,
        catalog: ProductCatalogRepository,
        access: Access,
        audit: AuditSink,
    ) : this(db, productDao, catalog, access, audit, false)

    constructor(db: StorviaDatabase, productDao: ProductDao) :
        this(db, productDao, null, OwnerAccess, NoAudit, true)

    constructor(db: StorviaDatabase, productDao: ProductDao, access: Access, audit: AuditSink) :
        this(db, productDao, null, access, audit, true)

    suspend fun save(product: ProductEntity, barcode: String?, isNew: Boolean): SaveResult {
        access.require(Permission.MANAGE_PRODUCTS)
        if (catalog?.isCloudMode() == true) return catalog.saveProduct(product, barcode)
        return db.withTransaction {
            access.require(Permission.MANAGE_PRODUCTS)
            if (product.parentProductId != null) {
                val parent = productDao.getById(product.parentProductId)
                if (parent == null || parent.deletedAt != null || parent.parentProductId != null ||
                    productDao.hasStockHistory(parent.id)
                ) return@withTransaction SaveResult.InvalidVariantParent
            }
            val bc = barcode?.trim()?.takeIf { it.isNotEmpty() }
            if (bc != null) {
                val existing = productDao.findByBarcode(bc)
                if (existing != null && existing.id != product.id) return@withTransaction SaveResult.DuplicateBarcode(existing.name)
            }
            if (isNew) productDao.insert(product)
            else {
                val before = productDao.getById(product.id)
                productDao.update(product.copy(updatedAt = System.currentTimeMillis(), dirty = true))
                if (before != null && (before.sellPricePaise != product.sellPricePaise || before.costPaise != product.costPaise))
                    audit.log(AuditAction.PRODUCT_PRICE_CHANGED,
                        "${product.name}: price ${Money(before.sellPricePaise).format()} -> ${Money(product.sellPricePaise).format()}, cost ${Money(before.costPaise).format()} -> ${Money(product.costPaise).format()}",
                        "PRODUCT", product.id)
            }
            // Keep an unchanged barcode as it is; otherwise retire the old one(s) and add the new one.
            if (productDao.getBarcodes(product.id) != listOfNotNull(bc)) {
                productDao.softDeleteBarcodes(product.id, System.currentTimeMillis())
                if (bc != null) productDao.insertBarcode(ProductBarcodeEntity(productId = product.id, barcode = bc))
            }
            product.parentProductId?.let { productDao.softDeleteBarcodes(it, System.currentTimeMillis()) }
            SaveResult.Saved(product.id)
        }
    }

    /** Soft delete: past invoices keep working. Barcodes are freed for reuse. */
    suspend fun archive(id: String) {
        access.require(Permission.MANAGE_PRODUCTS)
        if (catalog?.isCloudMode() == true) {
            catalog.archiveProduct(id)
            return
        }
        db.withTransaction {
            val product = productDao.getById(id)
            val name = product?.name ?: "product"
            val now = System.currentTimeMillis()
            if (product != null && product.parentProductId == null) {
                productDao.softDeleteVariantChildBarcodes(id, now)
                productDao.softDeleteVariantChildren(id, now)
            }
            productDao.softDelete(id, now)
            audit.log(AuditAction.PRODUCT_ARCHIVED, "Archived $name", "PRODUCT", id)
            productDao.softDeleteBarcodes(id, now)
        }
    }

    suspend fun hasStockOrHistory(id: String) = catalog?.hasStockOrHistory(id)
        ?: (productDao.currentStock(id) != 0L || productDao.hasStockHistory(id))
    suspend fun hasVariants(id: String) = catalog?.hasVariants(id) ?: productDao.hasVariants(id)
}
