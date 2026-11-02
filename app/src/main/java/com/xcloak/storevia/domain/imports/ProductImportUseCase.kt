package com.xcloak.storevia.domain.imports

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.ProductDao
import com.xcloak.storevia.core.database.dao.StockMovementDao
import com.xcloak.storevia.core.database.entity.*
import javax.inject.Inject

enum class DuplicatePolicy { SKIP, UPDATE }

data class ImportPreview(val newRows: Int, val existingRows: Int)
data class ImportSummary(val created: Int, val updated: Int, val skipped: Int)

/**
 * Applies validated rows in ONE transaction: all or nothing. A product "already exists" when its barcode
 * or SKU matches, or (only if the row has neither) when the name matches. Existing products are either
 * skipped or updated (price, cost, tax, reorder level); their stock and unit are never touched by an import.
 * New products get an OPENING batch + ledger entry when the row has a quantity.
 */
class ProductImportUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val productDao: ProductDao,
    private val batchDao: BatchDao,
    private val stockDao: StockMovementDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
) {
    private suspend fun findExisting(row: ImportRow): ProductEntity? {
        row.barcode?.let { productDao.findByBarcode(it) }?.let { return it }
        row.sku?.let { productDao.findBySku(it) }?.let { return it }
        return if (row.barcode == null && row.sku == null) productDao.findByName(row.name) else null
    }

    suspend fun preview(rows: List<ImportRow>): ImportPreview {
        val existing = rows.count { findExisting(it) != null }
        return ImportPreview(newRows = rows.size - existing, existingRows = existing)
    }

    suspend fun import(rows: List<ImportRow>, policy: DuplicatePolicy, now: Long = System.currentTimeMillis()): ImportSummary =
        db.withTransaction {
            access.require(Permission.IMPORT_DATA)
            var created = 0; var updated = 0; var skipped = 0
            for (row in rows) {
                val existing = findExisting(row)
                if (existing == null) { createProduct(row, now); created++; continue }
                if (policy == DuplicatePolicy.SKIP) { skipped++; continue }

                val owner = row.barcode?.let { productDao.findByBarcode(it) }
                if (owner != null && owner.id != existing.id) { skipped++; continue }   // barcode belongs to another product

                productDao.update(
                    existing.copy(
                        sku = row.sku ?: existing.sku, hsnCode = row.hsn ?: existing.hsnCode,
                        gstRateBp = row.gstRateBp, taxInclusive = row.taxInclusive,
                        costPaise = row.costPaise, sellPricePaise = row.pricePaise, mrpPaise = row.mrpPaise ?: existing.mrpPaise,
                        minStockMilli = row.minStockMilli, trackExpiry = existing.trackExpiry || row.expiry != null,
                        updatedAt = now, dirty = true,
                    )
                )
                if (row.barcode != null && productDao.getBarcodes(existing.id).isEmpty())
                    productDao.insertBarcode(ProductBarcodeEntity(productId = existing.id, barcode = row.barcode))
                updated++
            }
            audit.log(AuditAction.IMPORT, "Product import: $created added, $updated updated, $skipped skipped")
            ImportSummary(created, updated, skipped)
        }

    private suspend fun createProduct(row: ImportRow, now: Long) {
        val product = ProductEntity(
            name = row.name, sku = row.sku, unitId = row.unitId, hsnCode = row.hsn, gstRateBp = row.gstRateBp,
            taxInclusive = row.taxInclusive, mrpPaise = row.mrpPaise, sellPricePaise = row.pricePaise,
            costPaise = row.costPaise, trackExpiry = row.expiry != null, minStockMilli = row.minStockMilli,
            createdAt = now,
        )
        productDao.insert(product)
        row.barcode?.let { productDao.insertBarcode(ProductBarcodeEntity(productId = product.id, barcode = it)) }
        if (row.openingQtyMilli > 0) {
            val batch = BatchEntity(
                productId = product.id, batchNo = row.batchNo, expiryDate = row.expiry, costPaise = row.costPaise,
                mrpPaise = row.mrpPaise, qtyReceivedMilli = row.openingQtyMilli, qtyOnHandMilli = row.openingQtyMilli, receivedAt = now,
            )
            batchDao.insert(batch)
            stockDao.insert(
                StockMovementEntity(
                    productId = product.id, batchId = batch.id, type = MovementType.OPENING, qtyMilli = row.openingQtyMilli,
                    unitCostPaise = row.costPaise, refType = "IMPORT", refId = batch.id, createdAt = now,
                )
            )
        }
    }
}
