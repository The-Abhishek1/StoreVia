package com.xcloak.storevia.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xcloak.storevia.core.database.entity.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface StoreDao {
    @Query("SELECT * FROM store LIMIT 1") suspend fun get(): StoreEntity?
    @Query("SELECT * FROM store LIMIT 1") fun observe(): Flow<StoreEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(store: StoreEntity)
    @Query("UPDATE store SET nextInvoiceNo = nextInvoiceNo + 1, dirty = 1 WHERE id = :id")
    suspend fun incrementInvoiceNo(id: String)
}

@Dao
interface UnitDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(units: List<UnitEntity>)
    @Query("SELECT * FROM units ORDER BY name") fun observeAll(): Flow<List<UnitEntity>>
    @Query("SELECT COUNT(*) FROM units") suspend fun count(): Int
    @Query("SELECT * FROM units WHERE id = :id") suspend fun getById(id: String): UnitEntity?
}

data class ProductListRow(
    val id: String,
    val name: String,
    val sellPricePaise: Long,
    val unitSymbol: String,
    val onHandMilli: Long,
    val minStockMilli: Long,
    val categoryId: String?,
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE id = :id") fun observeById(id: String): Flow<ProductEntity?>
    @Query("SELECT barcode FROM product_barcodes WHERE productId = :productId AND deletedAt IS NULL") suspend fun getBarcodes(productId: String): List<String>
    @Query("SELECT barcode FROM product_barcodes WHERE productId = :productId AND deletedAt IS NULL LIMIT 1") fun observeBarcode(productId: String): Flow<String?>
    /** Soft delete, so the removal reaches other phones. */
    @Query("UPDATE product_barcodes SET deletedAt = :now, updatedAt = :now, dirty = 1 WHERE productId = :productId AND deletedAt IS NULL")
    suspend fun softDeleteBarcodes(productId: String, now: Long)
    @Query("UPDATE products SET deletedAt = :now, updatedAt = :now, dirty = 1 WHERE id = :id") suspend fun softDelete(id: String, now: Long)

    @Query("""SELECT p.id AS id, p.name AS name, p.sellPricePaise AS sellPricePaise, u.symbol AS unitSymbol,
                     COALESCE((SELECT SUM(b.qtyOnHandMilli) FROM batches b
                               WHERE b.productId = p.id AND b.deletedAt IS NULL), 0) AS onHandMilli,
                     p.minStockMilli AS minStockMilli, p.categoryId AS categoryId
              FROM products p JOIN units u ON u.id = p.unitId
              WHERE p.deletedAt IS NULL AND p.isActive = 1
                AND (:q = '' OR p.name LIKE '%' || :q || '%' OR p.sku LIKE '%' || :q || '%'
                     OR EXISTS (SELECT 1 FROM product_barcodes bc WHERE bc.productId = p.id AND bc.deletedAt IS NULL AND bc.barcode = :q))
              ORDER BY p.name COLLATE NOCASE""")
    fun listWithStock(q: String): Flow<List<ProductListRow>>

    @Query("""SELECT p.id AS id, p.name AS name, p.sellPricePaise AS sellPricePaise, u.symbol AS unitSymbol,
                     COALESCE((SELECT SUM(b.qtyOnHandMilli) FROM batches b
                               WHERE b.productId = p.id AND b.deletedAt IS NULL), 0) AS onHandMilli,
                     p.minStockMilli AS minStockMilli, p.categoryId AS categoryId
              FROM products p JOIN units u ON u.id = p.unitId
              WHERE p.deletedAt IS NULL AND p.isActive = 1 AND p.categoryId = :categoryId
                AND (:q = '' OR p.name LIKE '%' || :q || '%' OR p.sku LIKE '%' || :q || '%'
                     OR EXISTS (SELECT 1 FROM product_barcodes bc WHERE bc.productId = p.id AND bc.deletedAt IS NULL AND bc.barcode = :q))
              ORDER BY p.name COLLATE NOCASE""")
    fun listWithStockByCategory(q: String, categoryId: String): Flow<List<ProductListRow>>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(p: ProductEntity)
    @Update suspend fun update(p: ProductEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBarcode(b: ProductBarcodeEntity)

    @Query("SELECT * FROM products WHERE id = :id") suspend fun getById(id: String): ProductEntity?
    @Query("SELECT * FROM products WHERE deletedAt IS NULL AND sku = :sku LIMIT 1") suspend fun findBySku(sku: String): ProductEntity?
    @Query("SELECT * FROM products WHERE deletedAt IS NULL AND name = :name COLLATE NOCASE LIMIT 1") suspend fun findByName(name: String): ProductEntity?

    @Query("""SELECT p.* FROM products p
              JOIN product_barcodes b ON b.productId = p.id
              WHERE b.barcode = :barcode AND b.deletedAt IS NULL AND p.deletedAt IS NULL LIMIT 1""")
    suspend fun findByBarcode(barcode: String): ProductEntity?

    @Query("""SELECT * FROM products
              WHERE deletedAt IS NULL AND isActive = 1
                AND (name LIKE '%' || :q || '%' OR sku LIKE '%' || :q || '%')
              ORDER BY name LIMIT 100""")
    fun search(q: String): Flow<List<ProductEntity>>

    @Query("""SELECT COUNT(*) FROM products WHERE deletedAt IS NULL AND isActive = 1""")
    fun observeCount(): Flow<Int>
}

data class LowStockRow(val productId: String, val name: String, val onHandMilli: Long, val minStockMilli: Long)

@Dao
interface BatchDao {
    @Insert suspend fun insert(b: BatchEntity)
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun getById(id: String): BatchEntity?

    @Query("""SELECT * FROM batches WHERE productId = :productId AND deletedAt IS NULL AND qtyOnHandMilli > 0
              ORDER BY (expiryDate IS NULL), expiryDate ASC, receivedAt ASC""")
    fun observeForProduct(productId: String): Flow<List<BatchEntity>>

    /** FEFO: earliest expiry first, batches without expiry last. Expired batches excluded. */
    @Query("""SELECT * FROM batches
              WHERE productId = :productId AND deletedAt IS NULL AND qtyOnHandMilli > 0
                AND (expiryDate IS NULL OR expiryDate >= :today)
              ORDER BY (expiryDate IS NULL), expiryDate ASC, receivedAt ASC""")
    suspend fun fefoBatches(productId: String, today: LocalDate): List<BatchEntity>

    // Not marked dirty on purpose: the on-hand quantity is a cache of the stock ledger and is never synced as such.
    @Query("UPDATE batches SET qtyOnHandMilli = qtyOnHandMilli + :deltaMilli, updatedAt = :now WHERE id = :id")
    suspend fun adjustQty(id: String, deltaMilli: Long, now: Long = System.currentTimeMillis())

    @Query("""SELECT COUNT(*) FROM batches
              WHERE deletedAt IS NULL AND qtyOnHandMilli > 0 AND expiryDate IS NOT NULL
                AND expiryDate BETWEEN :from AND :to""")
    fun observeExpiringCount(from: LocalDate, to: LocalDate): Flow<Int>

    @Query("""SELECT p.id AS productId, p.name AS name,
                     COALESCE(SUM(b.qtyOnHandMilli), 0) AS onHandMilli, p.minStockMilli AS minStockMilli
              FROM products p LEFT JOIN batches b ON b.productId = p.id AND b.deletedAt IS NULL
              WHERE p.deletedAt IS NULL AND p.isActive = 1 AND p.minStockMilli > 0
              GROUP BY p.id
              HAVING onHandMilli < p.minStockMilli
              ORDER BY p.name""")
    fun observeLowStock(): Flow<List<LowStockRow>>
}

@Dao
interface StockMovementDao {
    @Insert suspend fun insert(m: StockMovementEntity)
    @Insert suspend fun insertAll(m: List<StockMovementEntity>)
    @Query("SELECT * FROM stock_movements WHERE productId = :productId ORDER BY createdAt DESC LIMIT :limit")
    fun observeForProduct(productId: String, limit: Int = 200): Flow<List<StockMovementEntity>>

    /** Consistency check: ledger total for a product. Must equal SUM(batch balances) + batchless negatives. */
    @Query("SELECT COALESCE(SUM(qtyMilli),0) FROM stock_movements WHERE productId = :productId")
    suspend fun ledgerTotal(productId: String): Long
}

data class CustomerBalanceRow(val id: String, val name: String, val phone: String?, val balancePaise: Long)

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE id = :id") fun observeById(id: String): Flow<CustomerEntity?>

    @Query("""SELECT c.id AS id, c.name AS name, c.phone AS phone,
                     COALESCE((SELECT SUM(l.amountPaise) FROM customer_ledger l WHERE l.customerId = c.id), 0) AS balancePaise
              FROM customers c
              WHERE c.deletedAt IS NULL
                AND (:q = '' OR c.name LIKE '%' || :q || '%' OR c.phone LIKE '%' || :q || '%')
              ORDER BY balancePaise DESC, c.name COLLATE NOCASE""")
    fun observeWithBalance(q: String): Flow<List<CustomerBalanceRow>>

    @Query("SELECT * FROM customer_ledger WHERE customerId = :id ORDER BY createdAt ASC, rowid ASC")
    fun observeLedger(id: String): Flow<List<CustomerLedgerEntity>>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM customer_ledger WHERE customerId = :id")
    suspend fun balance(id: String): Long

    /** Sum of what customers owe (customers with a positive balance only). */
    @Query("""SELECT COALESCE(SUM(b), 0) FROM
              (SELECT SUM(amountPaise) AS b FROM customer_ledger GROUP BY customerId HAVING SUM(amountPaise) > 0)""")
    fun observeTotalDue(): Flow<Long>

    @Query("SELECT * FROM customers WHERE id = :id") suspend fun getById(id: String): CustomerEntity?
    @Insert suspend fun insert(c: CustomerEntity)
    @Update suspend fun update(c: CustomerEntity)
    @Query("SELECT * FROM customers WHERE deletedAt IS NULL ORDER BY name") fun observeAll(): Flow<List<CustomerEntity>>
    @Insert suspend fun insertLedger(e: CustomerLedgerEntity)
    @Query("SELECT COALESCE(SUM(amountPaise),0) FROM customer_ledger WHERE customerId = :customerId")
    fun observeBalance(customerId: String): Flow<Long>
}

data class DaySummary(val totalPaise: Long, val count: Int)

@Dao
interface SaleDao {
    /** Oldest first, so khata payments settle the oldest bills first. */
    @Query("""SELECT * FROM sales WHERE customerId = :customerId AND status != 'CANCELLED'
                AND totalPaise - returnedPaise > paidPaise
              ORDER BY createdAt ASC""")
    suspend fun unpaidForCustomer(customerId: String): List<SaleEntity>

    @Query("UPDATE sales SET paidPaise = paidPaise + :amount, dirty = 1 WHERE id = :id")
    suspend fun addPaid(id: String, amount: Long)

    @Query("SELECT * FROM sales WHERE id = :id") suspend fun getById(id: String): SaleEntity?
    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId ORDER BY rowid") suspend fun getLines(saleId: String): List<SaleLineEntity>

    @Query("UPDATE sale_lines SET returnedQtyMilli = returnedQtyMilli + :qty, returnedAmountPaise = returnedAmountPaise + :amount WHERE id = :id")
    suspend fun addReturned(id: String, qty: Long, amount: Long)

    @Query("UPDATE sales SET returnedPaise = returnedPaise + :returned, paidPaise = paidPaise - :refunded, status = :status, dirty = 1 WHERE id = :id")
    suspend fun updateAfterReturn(id: String, returned: Long, refunded: Long, status: SaleStatus)

    @Insert suspend fun insertReturn(r: SaleReturnEntity)
    @Insert suspend fun insertReturnLines(l: List<SaleReturnLineEntity>)

    @Query("SELECT * FROM payments WHERE saleId = :saleId ORDER BY createdAt") suspend fun getPayments(saleId: String): List<PaymentEntity>

    @Query("UPDATE sales SET status = 'CANCELLED', cancelReason = :reason, cancelledAt = :now, dirty = 1 WHERE id = :id")
    suspend fun markCancelled(id: String, reason: String?, now: Long)

    @Insert suspend fun insertSale(s: SaleEntity)
    @Insert suspend fun insertLines(l: List<SaleLineEntity>)
    @Insert suspend fun insertPayments(p: List<PaymentEntity>)

    @Query("SELECT * FROM sales WHERE id = :id") fun observeById(id: String): Flow<SaleEntity?>
    @Query("SELECT * FROM sale_lines WHERE saleId = :saleId ORDER BY rowid") fun observeLines(saleId: String): Flow<List<SaleLineEntity>>
    @Query("SELECT * FROM payments WHERE saleId = :saleId ORDER BY createdAt") fun observePayments(saleId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM sales ORDER BY createdAt DESC LIMIT :limit") fun observeRecent(limit: Int = 100): Flow<List<SaleEntity>>

    @Query("""SELECT COALESCE(SUM(totalPaise),0) AS totalPaise, COUNT(*) AS count FROM sales
              WHERE status != 'CANCELLED' AND createdAt >= :fromMillis AND createdAt < :toMillis""")
    fun observeSummary(fromMillis: Long, toMillis: Long): Flow<DaySummary>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun getById(id: String): CategoryEntity?
    @Query("SELECT * FROM categories WHERE deletedAt IS NULL AND name = :name COLLATE NOCASE LIMIT 1") suspend fun findByName(name: String): CategoryEntity?
    @Insert suspend fun insert(c: CategoryEntity)
    @Update suspend fun update(c: CategoryEntity)
    @Query("UPDATE categories SET deletedAt = :now, updatedAt = :now, dirty = 1 WHERE id = :id") suspend fun softDelete(id: String, now: Long)
    @Query("UPDATE products SET categoryId = NULL, updatedAt = :now, dirty = 1 WHERE categoryId = :categoryId")
    suspend fun clearFromProducts(categoryId: String, now: Long)
    @Query("""SELECT categoryId AS id, COUNT(*) AS count FROM products
              WHERE deletedAt IS NULL AND categoryId IS NOT NULL GROUP BY categoryId""")
    fun observeProductCounts(): Flow<List<CategoryCount>>
}

data class CategoryCount(val id: String, val count: Int)

@Dao
interface HeldCartDao {
    @Insert suspend fun insert(h: HeldCartEntity)
    @Query("SELECT * FROM held_carts ORDER BY createdAt DESC") fun observeAll(): Flow<List<HeldCartEntity>>
    @Query("SELECT * FROM held_carts WHERE id = :id") suspend fun getById(id: String): HeldCartEntity?
    @Query("DELETE FROM held_carts WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state") suspend fun all(): List<SyncStateEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: SyncStateEntity)
    @Query("DELETE FROM sync_state") suspend fun clear()
}
