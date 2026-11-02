package com.xcloak.storevia.domain.sale

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.money.Money as Rupees
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.dao.StockMovementDao
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.core.money.Rounding
import javax.inject.Inject

/** Quantity (in thousandths) of one product the customer is bringing back. */
data class ReturnItem(val productId: String, val qtyMilli: Long)

/** How any money owed back to the customer is handled. */
sealed interface RefundMode {
    data class Money(val method: PaymentMethod) : RefundMode
    /** Keep it as advance credit on the customer's khata (needs a customer on the bill). */
    data object KhataCredit : RefundMode
}

data class ReturnPreview(
    val totalPaise: Long,
    /** Reduces what the customer still owes on this bill. */
    val dueReducedPaise: Long,
    /** Money (or advance credit) owed back to the customer. */
    val refundPaise: Long,
)

class ReturnException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOTHING_TO_RETURN, EXCEEDS_RETURNABLE, SALE_NOT_FOUND, SALE_CANCELLED, CREDIT_NEEDS_CUSTOMER }
}

/**
 * Records a return in one transaction:
 *  - puts stock back into the ORIGINAL batch (or batchless when the sale used negative stock),
 *  - first reduces what the customer still owes on that bill, then refunds the rest (money or khata credit),
 *  - updates the bill's returned/paid totals and status.
 * The original invoice is never edited.
 */
class ReturnItemsUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val saleDao: SaleDao,
    private val batchDao: BatchDao,
    private val stockDao: StockMovementDao,
    private val customerDao: CustomerDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
) {
    private class Piece(val line: SaleLineEntity, val qtyMilli: Long, val amountPaise: Long)
    private class Computed(val sale: SaleEntity, val lines: List<SaleLineEntity>, val pieces: List<Piece>, val preview: ReturnPreview)

    suspend fun preview(saleId: String, items: List<ReturnItem>): ReturnPreview = compute(saleId, items).preview

    suspend operator fun invoke(
        saleId: String,
        items: List<ReturnItem>,
        refund: RefundMode = RefundMode.Money(PaymentMethod.CASH),
        now: Long = System.currentTimeMillis(),
        /** false when another action (cancel bill) writes its own audit entry. */
        logAudit: Boolean = true,
    ): String = db.withTransaction {
        access.require(Permission.PROCESS_RETURN)
        val c = compute(saleId, items)
        val sale = c.sale
        val pv = c.preview
        if (pv.refundPaise > 0 && refund is RefundMode.KhataCredit && sale.customerId == null)
            throw ReturnException(ReturnException.Reason.CREDIT_NEEDS_CUSTOMER)

        val returnId = java.util.UUID.randomUUID().toString()

        for (p in c.pieces) {
            saleDao.addReturned(p.line.id, p.qtyMilli, p.amountPaise)
            p.line.batchId?.let { batchDao.adjustQty(it, p.qtyMilli, now) }
            stockDao.insert(
                StockMovementEntity(
                    productId = p.line.productId, batchId = p.line.batchId, type = MovementType.SALE_RETURN,
                    qtyMilli = p.qtyMilli, unitCostPaise = p.line.costSnapshotPaise,
                    refType = "RETURN", refId = returnId, createdAt = now,
                )
            )
        }

        val refundMethod = (refund as? RefundMode.Money)?.method
        val keptAsCredit = refund is RefundMode.KhataCredit && pv.refundPaise > 0
        saleDao.insertReturn(
            SaleReturnEntity(
                id = returnId, saleId = saleId, customerId = sale.customerId, totalPaise = pv.totalPaise,
                dueReducedPaise = pv.dueReducedPaise, refundPaise = pv.refundPaise,
                refundMethod = if (pv.refundPaise > 0) refundMethod else null, keptAsCredit = keptAsCredit, createdAt = now,
            )
        )
        saleDao.insertReturnLines(c.pieces.map {
            SaleReturnLineEntity(
                returnId = returnId, saleLineId = it.line.id, productId = it.line.productId, batchId = it.line.batchId,
                qtyMilli = it.qtyMilli, amountPaise = it.amountPaise, costSnapshotPaise = it.line.costSnapshotPaise,
            )
        })

        // Customer's khata: what they owed goes down; advance credit (if chosen) goes down further.
        val customerId = sale.customerId
        if (customerId != null) {
            val ledgerCredit = pv.dueReducedPaise + if (keptAsCredit) pv.refundPaise else 0L
            if (ledgerCredit > 0) customerDao.insertLedger(
                CustomerLedgerEntity(customerId = customerId, type = LedgerType.RETURN_CREDIT, amountPaise = -ledgerCredit,
                    refType = "RETURN", refId = returnId, createdAt = now)
            )
        }
        // Money actually handed back is recorded as a negative payment.
        if (pv.refundPaise > 0 && refundMethod != null) {
            saleDao.insertPayments(listOf(
                PaymentEntity(saleId = saleId, customerId = customerId, method = refundMethod,
                    amountPaise = -pv.refundPaise, reference = "RETURN", createdAt = now)
            ))
        }

        val allReturned = c.lines.all { l ->
            val extra = c.pieces.filter { it.line.id == l.id }.sumOf { it.qtyMilli }
            l.returnedQtyMilli + extra >= l.qtyMilli
        }
        saleDao.updateAfterReturn(
            id = saleId, returned = pv.totalPaise, refunded = pv.refundPaise,
            status = if (allReturned) SaleStatus.RETURNED else SaleStatus.PARTIAL_RETURN,
        )
        if (logAudit) audit.log(AuditAction.RETURN, "Return ${Rupees(pv.totalPaise).format()} on ${sale.invoiceNo}", "SALE", saleId)
        returnId
    }

    private suspend fun compute(saleId: String, items: List<ReturnItem>): Computed {
        val sale = saleDao.getById(saleId) ?: throw ReturnException(ReturnException.Reason.SALE_NOT_FOUND)
        if (sale.status == SaleStatus.CANCELLED) throw ReturnException(ReturnException.Reason.SALE_CANCELLED)
        val lines = saleDao.getLines(saleId)

        val wanted = items.filter { it.qtyMilli > 0 }
        if (wanted.isEmpty()) throw ReturnException(ReturnException.Reason.NOTHING_TO_RETURN)

        val pieces = mutableListOf<Piece>()
        for (item in wanted) {
            // Spread across the product's lines (one per batch) in sale order.
            var left = item.qtyMilli
            for (line in lines.filter { it.productId == item.productId }) {
                if (left == 0L) break
                val returnable = line.qtyMilli - line.returnedQtyMilli
                val take = minOf(returnable, left)
                if (take <= 0) continue
                // Last unit of a line takes the remainder so refunds never drift by a paisa.
                val amount = if (take == returnable) line.lineTotalPaise - line.returnedAmountPaise
                else Rounding.divHalfUp(line.lineTotalPaise * take, line.qtyMilli)
                pieces += Piece(line, take, amount)
                left -= take
            }
            if (left > 0) throw ReturnException(ReturnException.Reason.EXCEEDS_RETURNABLE)
        }

        val total = pieces.sumOf { it.amountPaise }
        val stillOwed = (sale.totalPaise - sale.returnedPaise - sale.paidPaise).coerceAtLeast(0)
        val dueReduced = if (sale.customerId != null) minOf(total, stillOwed) else 0L
        return Computed(sale, lines, pieces, ReturnPreview(total, dueReduced, total - dueReduced))
    }
}
