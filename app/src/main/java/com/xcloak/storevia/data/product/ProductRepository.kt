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
}

class ProductRepository @Inject constructor(
    private val db: StorviaDatabase,
    private val productDao: ProductDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
) {
    suspend fun save(product: ProductEntity, barcode: String?, isNew: Boolean): SaveResult =
        db.withTransaction {
            access.require(Permission.MANAGE_PRODUCTS)
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
            SaveResult.Saved(product.id)
        }

    /** Soft delete: past invoices keep working. Barcodes are freed for reuse. */
    suspend fun archive(id: String) = db.withTransaction {
        access.require(Permission.MANAGE_PRODUCTS)
        val name = productDao.getById(id)?.name ?: "product"
        productDao.softDelete(id, System.currentTimeMillis())
        audit.log(AuditAction.PRODUCT_ARCHIVED, "Archived $name", "PRODUCT", id)
        productDao.softDeleteBarcodes(id, System.currentTimeMillis())
    }
}
