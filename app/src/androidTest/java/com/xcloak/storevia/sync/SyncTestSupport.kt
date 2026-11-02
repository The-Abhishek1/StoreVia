package com.xcloak.storevia.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xcloak.storevia.core.access.NoAudit
import com.xcloak.storevia.core.access.OwnerAccess
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.core.sync.SyncEngine
import com.xcloak.storevia.core.sync.SyncRemote
import com.xcloak.storevia.domain.customer.ReceivePaymentUseCase
import com.xcloak.storevia.domain.sale.*
import com.xcloak.storevia.domain.stock.AdjustStockUseCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** A stand-in for Supabase: keeps rows per table and stamps each write with an increasing server time. */
class FakeRemote : SyncRemote {
    private val tables = HashMap<String, LinkedHashMap<String, JsonObject>>()
    private var clock = 0L
    private fun stamp() = Instant.ofEpochSecond(1_700_000_000L + ++clock).toString()
    private fun id(r: JsonObject) = (r["id"] as JsonPrimitive).content
    private fun ts(r: JsonObject) = OffsetDateTime.parse((r["server_updated_at"] as JsonPrimitive).content).toInstant()

    fun count(table: String) = tables[table]?.size ?: 0

    override suspend fun push(table: String, rows: List<JsonObject>, ignoreDuplicates: Boolean) {
        val t = tables.getOrPut(table) { LinkedHashMap() }
        for (r in rows) {
            if (ignoreDuplicates && t.containsKey(id(r))) continue
            t[id(r)] = JsonObject(r + ("server_updated_at" to JsonPrimitive(stamp())))
        }
    }

    override suspend fun pull(table: String, afterTs: String?, afterId: String, limit: Int): List<JsonObject> {
        val after = afterTs?.let { OffsetDateTime.parse(it).toInstant() }
        return tables[table].orEmpty().values
            .filter { r -> after == null || ts(r).isAfter(after) || (ts(r) == after && id(r) > afterId) }
            .sortedWith(compareBy({ ts(it) }, { id(it) })).take(limit)
    }
}

/** One simulated phone: its own database and the real use cases, with its own invoice series. */
class Phone(series: String, val storeId: String = "S1") {
    val db: StorviaDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), StorviaDatabase::class.java)
        .allowMainThreadQueries().build()
    private val invoice = object : InvoiceSeries { override fun current() = series }

    val sell = CompleteSaleUseCase(db, db.storeDao(), db.productDao(), db.batchDao(), db.stockMovementDao(), db.saleDao(), db.customerDao(), OwnerAccess, NoAudit, invoice)
    val receive = ReceiveStockUseCase(db, db.batchDao(), db.stockMovementDao())
    val giveBack = ReturnItemsUseCase(db, db.saleDao(), db.batchDao(), db.stockMovementDao(), db.customerDao())
    val cancel = CancelSaleUseCase(db, db.saleDao(), giveBack)
    val adjust = AdjustStockUseCase(db, db.batchDao(), db.stockMovementDao())
    val takePayment = ReceivePaymentUseCase(db, db.customerDao(), db.saleDao())

    fun engine(remote: SyncRemote, overlapSeconds: Long = 10) = SyncEngine(db, remote, storeId, overlapSeconds = overlapSeconds)
    fun close() = db.close()

    fun rows(sql: String): List<List<String>> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) ?: "null" }) }
    }

    /** Everything that must be identical on every phone once they have synced. */
    fun snapshot(): List<List<List<String>>> = listOf(
        rows("SELECT id, name, sellPricePaise FROM products ORDER BY id"),
        rows("SELECT id, qtyOnHandMilli FROM batches ORDER BY id"),
        rows("SELECT id, invoiceNo, totalPaise, paidPaise, returnedPaise, status FROM sales ORDER BY id"),
        rows("SELECT id, returnedQtyMilli, returnedAmountPaise FROM sale_lines ORDER BY id"),
        rows("SELECT customerId, SUM(amountPaise) FROM customer_ledger GROUP BY customerId ORDER BY customerId"),
        rows("SELECT productId, SUM(qtyMilli) FROM stock_movements GROUP BY productId ORDER BY productId"),
        rows("SELECT (SELECT COUNT(*) FROM payments), (SELECT COUNT(*) FROM sale_returns), (SELECT COUNT(*) FROM stock_movements), (SELECT COUNT(*) FROM customer_ledger)"),
    )

    /** The shop every test uses: one product at ₹10 (18% GST inclusive), 10 pieces in stock. Call on the first phone only. */
    suspend fun seedShop(): ProductEntity {
        db.storeDao().upsert(StoreEntity(id = storeId, name = "Ravi Stores"))
        val unit = UnitEntity(name = "Piece", symbol = "pcs", allowDecimal = false)
        db.unitDao().insertAll(listOf(unit))
        val p = ProductEntity(name = "Biscuits", unitId = unit.id, sellPricePaise = 1000, costPaise = 700, gstRateBp = 1800, taxInclusive = true)
        db.productDao().insert(p)
        receive(p.id, 10_000, 700, "A", LocalDate.now().plusDays(60))
        return p
    }

    suspend fun sellPieces(p: ProductEntity, pcs: Long, paid: Long = pcs * 1000, customerId: String? = null): String =
        sell(listOf(CartItem(p.id, pcs * 1000, 1000)), if (paid > 0) listOf(PaymentInput(PaymentMethod.CASH, paid)) else emptyList(), customerId = customerId)
}
