package com.xcloak.storevia.core.sync

import androidx.room.withTransaction
import com.xcloak.storevia.core.database.StorviaDatabase

/**
 * A few columns are caches of facts that sync as permanent records: batch on-hand quantity (from the stock
 * ledger), and each bill's returned / paid amounts and status (from returns and payments). Phones never trade
 * these directly; after new records arrive each phone recomputes them, so everyone ends with the same numbers.
 * The statements don't touch the "dirty" flag: recomputing is not an edit.
 */
class CacheRebuilder(private val db: StorviaDatabase) {
    suspend fun recomputeAll() = db.withTransaction {
        val sdb = db.openHelper.writableDatabase
        STATEMENTS.forEach { sdb.execSQL(it) }
    }

    companion object {
        val STATEMENTS = listOf(
            """UPDATE batches SET qtyOnHandMilli =
                 COALESCE((SELECT SUM(m.qtyMilli) FROM stock_movements m WHERE m.batchId = batches.id), 0)""",
            """UPDATE sale_lines SET
                 returnedQtyMilli = COALESCE((SELECT SUM(r.qtyMilli) FROM sale_return_lines r WHERE r.saleLineId = sale_lines.id), 0),
                 returnedAmountPaise = COALESCE((SELECT SUM(r.amountPaise) FROM sale_return_lines r WHERE r.saleLineId = sale_lines.id), 0)""",
            // paid = money received on the bill, minus refunds handed back; a refund kept as khata credit never left the shop.
            """UPDATE sales SET
                 returnedPaise = COALESCE((SELECT SUM(r.totalPaise) FROM sale_returns r WHERE r.saleId = sales.id), 0),
                 paidPaise = COALESCE((SELECT SUM(p.amountPaise) FROM payments p WHERE p.saleId = sales.id), 0)
                           - COALESCE((SELECT SUM(r.refundPaise) FROM sale_returns r WHERE r.saleId = sales.id AND r.keptAsCredit = 1), 0)""",
            """UPDATE sales SET status = CASE
                 WHEN status = 'CANCELLED' THEN 'CANCELLED'
                 WHEN EXISTS (SELECT 1 FROM sale_lines l WHERE l.saleId = sales.id)
                      AND NOT EXISTS (SELECT 1 FROM sale_lines l WHERE l.saleId = sales.id AND l.returnedQtyMilli < l.qtyMilli) THEN 'RETURNED'
                 WHEN EXISTS (SELECT 1 FROM sale_lines l WHERE l.saleId = sales.id AND l.returnedQtyMilli > 0) THEN 'PARTIAL_RETURN'
                 ELSE 'COMPLETED' END""",
        )
    }
}
