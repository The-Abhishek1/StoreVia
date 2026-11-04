package com.xcloak.storevia.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.xcloak.storevia.core.database.entity.*
import kotlinx.coroutines.flow.Flow

data class SupplierBalanceRow(val id: String, val name: String, val phone: String?, val balancePaise: Long)

data class PurchaseListRow(
    val id: String, val invoiceNo: String?, val supplierName: String?, val purchaseDate: Long,
    val totalPaise: Long, val paidPaise: Long,
)

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers WHERE id = :id AND deletedAt IS NULL") fun observeById(id: String): Flow<SupplierEntity?>
    @Query("SELECT * FROM suppliers WHERE id = :id AND deletedAt IS NULL") suspend fun getById(id: String): SupplierEntity?
    @Insert suspend fun insert(s: SupplierEntity)
    @Update suspend fun update(s: SupplierEntity)

    @Query("""SELECT s.id AS id, s.name AS name, s.phone AS phone,
                     COALESCE((SELECT SUM(l.amountPaise) FROM supplier_ledger l WHERE l.supplierId = s.id), 0) AS balancePaise
              FROM suppliers s
              WHERE s.deletedAt IS NULL AND (:q = '' OR s.name LIKE '%' || :q || '%' OR s.phone LIKE '%' || :q || '%')
              ORDER BY balancePaise DESC, s.name COLLATE NOCASE""")
    fun observeWithBalance(q: String): Flow<List<SupplierBalanceRow>>

    @Query("SELECT * FROM suppliers WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<SupplierEntity>>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM supplier_ledger WHERE supplierId = :id") suspend fun balance(id: String): Long
    @Query("SELECT * FROM supplier_ledger WHERE supplierId = :id ORDER BY createdAt ASC, rowid ASC") fun observeLedger(id: String): Flow<List<SupplierLedgerEntity>>
    @Insert suspend fun insertLedger(e: SupplierLedgerEntity)

    /** What you owe all suppliers together (suppliers you are in credit with are not netted off). */
    @Query("""SELECT COALESCE(SUM(b), 0) FROM
              (SELECT SUM(l.amountPaise) AS b FROM supplier_ledger l
               INNER JOIN suppliers s ON s.id = l.supplierId AND s.deletedAt IS NULL
               GROUP BY l.supplierId HAVING SUM(l.amountPaise) > 0)""")
    fun observeTotalPayable(): Flow<Long>
}

@Dao
interface PurchaseDao {
    @Insert suspend fun insert(p: PurchaseEntity)
    @Insert suspend fun insertLines(l: List<PurchaseLineEntity>)

    @Query("""SELECT p.id AS id, p.invoiceNo AS invoiceNo, s.name AS supplierName, p.purchaseDate AS purchaseDate,
                     p.totalPaise AS totalPaise, p.paidPaise AS paidPaise
              FROM purchases p LEFT JOIN suppliers s ON s.id = p.supplierId
              WHERE p.branchId = :branchId
                AND (:query = '' OR COALESCE(p.invoiceNo, '') LIKE '%' || :query || '%'
                     OR COALESCE(s.name, '') LIKE '%' || :query || '%')
                AND (:unpaidOnly = 0 OR p.paidPaise < p.totalPaise)
              ORDER BY p.purchaseDate DESC, p.createdAt DESC LIMIT :limit""")
    fun observeRecent(
        limit: Int = 200,
        branchId: String = "default",
        query: String = "",
        unpaidOnly: Boolean = false,
    ): Flow<List<PurchaseListRow>>

    @Query("SELECT * FROM purchases WHERE id = :id AND :branchId IS NOT NULL AND branchId = :branchId")
    fun observeById(id: String, branchId: String?): Flow<PurchaseEntity?>
    @Query("""SELECT * FROM purchase_lines WHERE purchaseId = :purchaseId
              AND EXISTS (SELECT 1 FROM purchases p WHERE p.id = :purchaseId AND :branchId IS NOT NULL AND p.branchId = :branchId)
              ORDER BY rowid""")
    fun observeLines(purchaseId: String, branchId: String?): Flow<List<PurchaseLineEntity>>
    @Query("SELECT * FROM purchase_lines WHERE purchaseId = :purchaseId ORDER BY rowid") suspend fun getLines(purchaseId: String): List<PurchaseLineEntity>
    @Query("SELECT * FROM purchases WHERE id = :id") suspend fun getById(id: String): PurchaseEntity?
}
