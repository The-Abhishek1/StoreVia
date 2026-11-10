package com.xcloak.storevia.domain.sale

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SaleStatus
import javax.inject.Inject

class CancelSaleException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_FOUND, ALREADY_CANCELLED, HAS_RETURNS }
}

/**
 * Cancels a whole bill. It is recorded as a full return (so stock goes back to the original batches and money /
 * khata are reversed exactly like any return, leaving a paper trail), and the bill is then marked CANCELLED.
 * The refund goes back the way the customer paid (the method of their largest payment). Only untouched bills
 * (no earlier returns) can be cancelled; otherwise use Return items.
 */
class CancelSaleUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val saleDao: SaleDao,
    private val returnItems: ReturnItemsUseCase,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
    private val cloudOperations: SalesInventoryOperations = LocalSalesInventoryOperations,
) {
    suspend operator fun invoke(saleId: String, reason: String?, now: Long = System.currentTimeMillis()) {
        access.require(Permission.CANCEL_BILL)
        if (cloudOperations.isCloudMode()) {
            cloudOperations.cancelSale(saleId, reason?.trim()?.ifEmpty { null }, now)
            return
        }
        db.withTransaction {
            val sale = saleDao.getById(saleId) ?: throw CancelSaleException(CancelSaleException.Reason.NOT_FOUND)
            if (sale.status == SaleStatus.CANCELLED) throw CancelSaleException(CancelSaleException.Reason.ALREADY_CANCELLED)
            if (sale.status != SaleStatus.COMPLETED || sale.returnedPaise > 0) throw CancelSaleException(CancelSaleException.Reason.HAS_RETURNS)

            val items = saleDao.getLines(saleId).groupBy { it.productId }.map { (pid, lines) -> ReturnItem(pid, lines.sumOf { it.qtyMilli }) }
            val method = saleDao.getPayments(saleId).filter { it.amountPaise > 0 }.maxByOrNull { it.amountPaise }?.method ?: PaymentMethod.CASH
            returnItems(saleId, items, RefundMode.Money(method), now, logAudit = false)
            saleDao.markCancelled(saleId, reason?.trim()?.ifEmpty { null }, now)
            audit.log(AuditAction.CANCEL_BILL, "Cancelled ${sale.invoiceNo} (${Money(sale.totalPaise).format()})" + (reason?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""), "SALE", saleId)
        }
    }
}
