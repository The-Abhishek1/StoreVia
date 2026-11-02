package com.xcloak.storevia.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.xcloak.storevia.core.database.entity.PaymentMethod
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class SalesAgg(val grossPaise: Long, val bills: Int, val discountPaise: Long)
data class LineAgg(val taxablePaise: Long, val costPaise: Long, val taxPaise: Long)
data class ReturnLineAgg(val taxablePaise: Long, val costPaise: Long)
data class PaymentTotalRow(val method: PaymentMethod, val totalPaise: Long)
data class TopProductRow(val productId: String, val name: String, val unitSymbol: String, val qtyMilli: Long, val revenuePaise: Long)
data class StockValue(val costPaise: Long, val retailPaise: Long)
data class BatchAgg(val batches: Int, val costPaise: Long)
data class ReorderRow(
    val productId: String, val name: String, val unitSymbol: String, val allowDecimal: Boolean,
    val onHandMilli: Long, val minStockMilli: Long, val costPaise: Long,
)

/**
 * Report queries. Sales are counted on the sale date; returns on the return date
 * (like a credit note), so a period's net sales = sales made - returns recorded.
 * Money is integer paise throughout; per-row division truncates, so profit is an estimate.
 */
@Dao
interface ReportDao {
    @Query("""SELECT COALESCE(SUM(totalPaise), 0) AS grossPaise, COUNT(*) AS bills, COALESCE(SUM(discountPaise), 0) AS discountPaise
              FROM sales WHERE status != 'CANCELLED' AND createdAt >= :from AND createdAt < :to""")
    fun sales(from: Long, to: Long): Flow<SalesAgg>

    @Query("""SELECT COALESCE(SUM(sl.taxableValuePaise), 0) AS taxablePaise,
                     COALESCE(SUM(sl.qtyMilli * sl.costSnapshotPaise / 1000), 0) AS costPaise,
                     COALESCE(SUM(sl.cgstPaise + sl.sgstPaise + sl.igstPaise), 0) AS taxPaise
              FROM sale_lines sl JOIN sales s ON s.id = sl.saleId
              WHERE s.status != 'CANCELLED' AND s.createdAt >= :from AND s.createdAt < :to""")
    fun lines(from: Long, to: Long): Flow<LineAgg>

    @Query("""SELECT COALESCE(SUM(r.totalPaise), 0) FROM sale_returns r JOIN sales s ON s.id = r.saleId
              WHERE s.status != 'CANCELLED' AND r.createdAt >= :from AND r.createdAt < :to""")
    fun returnsTotal(from: Long, to: Long): Flow<Long>

    @Query("""SELECT COALESCE(SUM(rl.amountPaise * sl.taxableValuePaise / sl.lineTotalPaise), 0) AS taxablePaise,
                     COALESCE(SUM(rl.qtyMilli * rl.costSnapshotPaise / 1000), 0) AS costPaise
              FROM sale_return_lines rl
              JOIN sale_returns r ON r.id = rl.returnId
              JOIN sale_lines sl ON sl.id = rl.saleLineId
              JOIN sales s ON s.id = r.saleId
              WHERE s.status != 'CANCELLED' AND r.createdAt >= :from AND r.createdAt < :to AND sl.lineTotalPaise > 0""")
    fun returnLines(from: Long, to: Long): Flow<ReturnLineAgg>

    /** Money in/out by method, refunds included as negatives. */
    @Query("""SELECT method AS method, SUM(amountPaise) AS totalPaise FROM payments
              WHERE createdAt >= :from AND createdAt < :to GROUP BY method ORDER BY totalPaise DESC""")
    fun paymentsByMethod(from: Long, to: Long): Flow<List<PaymentTotalRow>>

    @Query("""SELECT sl.productId AS productId, MAX(sl.nameSnapshot) AS name, COALESCE(MAX(u.symbol), '') AS unitSymbol,
                     SUM(sl.qtyMilli) AS qtyMilli, SUM(sl.lineTotalPaise) AS revenuePaise
              FROM sale_lines sl
              JOIN sales s ON s.id = sl.saleId
              LEFT JOIN products p ON p.id = sl.productId
              LEFT JOIN units u ON u.id = p.unitId
              WHERE s.status != 'CANCELLED' AND s.createdAt >= :from AND s.createdAt < :to
              GROUP BY sl.productId ORDER BY revenuePaise DESC LIMIT :limit""")
    fun topProducts(from: Long, to: Long, limit: Int): Flow<List<TopProductRow>>

    @Query("""SELECT COALESCE(SUM(b.qtyOnHandMilli * b.costPaise / 1000), 0) AS costPaise,
                     COALESCE(SUM(b.qtyOnHandMilli * p.sellPricePaise / 1000), 0) AS retailPaise
              FROM batches b JOIN products p ON p.id = b.productId
              WHERE b.deletedAt IS NULL AND b.qtyOnHandMilli > 0 AND p.deletedAt IS NULL""")
    fun stockValue(): Flow<StockValue>

    /** Batches with stock whose expiry date falls in [from, to] (inclusive). */
    @Query("""SELECT COUNT(*) AS batches, COALESCE(SUM(qtyOnHandMilli * costPaise / 1000), 0) AS costPaise
              FROM batches WHERE deletedAt IS NULL AND qtyOnHandMilli > 0 AND expiryDate IS NOT NULL
                AND expiryDate BETWEEN :from AND :to""")
    fun expiryBetween(from: LocalDate, to: LocalDate): Flow<BatchAgg>

    /** Products below their reorder level, with what they cost to buy. */
    @Query("""SELECT p.id AS productId, p.name AS name, u.symbol AS unitSymbol, u.allowDecimal AS allowDecimal,
                     COALESCE((SELECT SUM(b.qtyOnHandMilli) FROM batches b WHERE b.productId = p.id AND b.deletedAt IS NULL), 0) AS onHandMilli,
                     p.minStockMilli AS minStockMilli, p.costPaise AS costPaise
              FROM products p JOIN units u ON u.id = p.unitId
              WHERE p.deletedAt IS NULL AND p.isActive = 1 AND p.minStockMilli > 0
              GROUP BY p.id HAVING onHandMilli < p.minStockMilli
              ORDER BY p.name COLLATE NOCASE""")
    fun reorderRows(): Flow<List<ReorderRow>>
}
