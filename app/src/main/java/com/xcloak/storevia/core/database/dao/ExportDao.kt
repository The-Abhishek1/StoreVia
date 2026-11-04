package com.xcloak.storevia.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import java.time.LocalDate

data class ProductExportRow(
    val name: String, val sku: String?, val barcode: String?, val unitSymbol: String, val hsnCode: String?,
    val gstRateBp: Int, val taxInclusive: Boolean, val costPaise: Long, val sellPricePaise: Long, val mrpPaise: Long?,
    val minStockMilli: Long, val onHandMilli: Long, val attributesJson: String,
)

data class BatchExportRow(
    val productName: String, val unitSymbol: String, val batchNo: String?, val expiryDate: LocalDate?,
    val qtyOnHandMilli: Long, val costPaise: Long,
)

data class SaleExportRow(
    val invoiceNo: String, val createdAt: Long, val customerName: String?, val status: String,
    val subtotalPaise: Long, val taxPaise: Long, val discountPaise: Long, val roundOffPaise: Long,
    val totalPaise: Long, val returnedPaise: Long, val paidPaise: Long, val paymentMode: String,
)
data class SaleLineExportRow(
    val invoiceNo: String,
    val sku: String?,
    val productName: String,
    val qtyMilli: Long,
    val unitPricePaise: Long,
)

@Dao
interface ExportDao {
    @Query("""SELECT p.name AS name, p.sku AS sku,
                     (SELECT bc.barcode FROM product_barcodes bc WHERE bc.productId = p.id AND bc.deletedAt IS NULL LIMIT 1) AS barcode,
                     u.symbol AS unitSymbol, p.hsnCode AS hsnCode, p.gstRateBp AS gstRateBp, p.taxInclusive AS taxInclusive,
                     p.costPaise AS costPaise, p.sellPricePaise AS sellPricePaise, p.mrpPaise AS mrpPaise,
                     p.minStockMilli AS minStockMilli, p.attributesJson AS attributesJson,
                     COALESCE((SELECT SUM(b.qtyOnHandMilli) FROM batches b WHERE b.productId = p.id AND b.deletedAt IS NULL), 0) AS onHandMilli
              FROM products p JOIN units u ON u.id = p.unitId
              WHERE p.deletedAt IS NULL ORDER BY p.name COLLATE NOCASE""")
    suspend fun products(): List<ProductExportRow>

    @Query("""SELECT p.name AS productName, u.symbol AS unitSymbol, b.batchNo AS batchNo, b.expiryDate AS expiryDate,
                     b.qtyOnHandMilli AS qtyOnHandMilli, b.costPaise AS costPaise
              FROM batches b JOIN products p ON p.id = b.productId JOIN units u ON u.id = p.unitId
              WHERE b.deletedAt IS NULL AND b.qtyOnHandMilli > 0
              ORDER BY p.name COLLATE NOCASE, (b.expiryDate IS NULL), b.expiryDate""")
    suspend fun batches(): List<BatchExportRow>

    @Query("""SELECT s.invoiceNo AS invoiceNo, s.createdAt AS createdAt, c.name AS customerName, s.status AS status,
                     s.subtotalPaise AS subtotalPaise, s.taxPaise AS taxPaise, s.discountPaise AS discountPaise,
                     s.roundOffPaise AS roundOffPaise, s.totalPaise AS totalPaise, s.returnedPaise AS returnedPaise,
                     s.paidPaise AS paidPaise,
                     CASE WHEN s.totalPaise - s.returnedPaise > s.paidPaise THEN 'CREDIT'
                          ELSE COALESCE((SELECT MIN(p.method) FROM payments p WHERE p.saleId = s.id AND p.amountPaise > 0), 'OTHER')
                     END AS paymentMode
              FROM sales s LEFT JOIN customers c ON c.id = s.customerId ORDER BY s.createdAt""")
    suspend fun sales(): List<SaleExportRow>

    @Query("""SELECT s.invoiceNo AS invoiceNo, p.sku AS sku, sl.nameSnapshot AS productName,
                     sl.qtyMilli AS qtyMilli, sl.unitPricePaise AS unitPricePaise
              FROM sale_lines sl JOIN sales s ON s.id = sl.saleId
              LEFT JOIN products p ON p.id = sl.productId
              ORDER BY s.createdAt, s.invoiceNo, sl.rowid""")
    suspend fun saleLines(): List<SaleLineExportRow>
}
