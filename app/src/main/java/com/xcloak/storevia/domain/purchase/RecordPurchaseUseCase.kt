package com.xcloak.storevia.domain.purchase

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.*
import com.xcloak.storevia.core.database.dao.activeBranchId
import com.xcloak.storevia.core.database.entity.*
import com.xcloak.storevia.core.money.Money
import java.time.LocalDate
import javax.inject.Inject

data class PurchaseItem(
    val productId: String,
    val qtyMilli: Long,
    val unitCostPaise: Long,
    val batchNo: String? = null,
    val expiry: LocalDate? = null,
    val serialNumbers: List<String> = emptyList(),
)

class PurchaseException(val reason: Reason, val detail: String? = null) : Exception(reason.name) {
    enum class Reason {
        NO_LINES, INVALID_QTY, INVALID_COST, PRODUCT_NOT_FOUND, EXPIRY_REQUIRED, INVALID_TOTAL,
        PAID_EXCEEDS_TOTAL, SUPPLIER_REQUIRED_FOR_CREDIT, SERIAL_COUNT, SERIAL_DUPLICATE,
    }
}

/**
 * Records a purchase in ONE transaction: a batch + PURCHASE ledger row per line, the purchase record, and the
 * supplier's payable (the bill total goes on what you owe; anything paid now comes straight off).
 *
 * [billTotalPaise] is what the supplier bill says. It may exceed the sum of the lines (tax, freight); batch cost
 * still uses the line costs. GST input credit on purchases is not modelled yet.
 */
class RecordPurchaseUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val productDao: ProductDao,
    private val batchDao: BatchDao,
    private val stockDao: StockMovementDao,
    private val purchaseDao: PurchaseDao,
    private val supplierDao: SupplierDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
    private val serialNumberDao: SerialNumberDao = db.serialNumberDao(),
) {
    suspend operator fun invoke(
        items: List<PurchaseItem>,
        supplierId: String?,
        invoiceNo: String?,
        purchaseDateMillis: Long,
        billTotalPaise: Long? = null,
        paidPaise: Long = 0,
        payMethod: PaymentMethod? = null,
        notes: String? = null,
        /** Keep each product's catalogue cost in step with what it last cost to buy. */
        updateProductCost: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): String {
        access.require(Permission.MANAGE_PURCHASES)
        if (items.isEmpty()) throw PurchaseException(PurchaseException.Reason.NO_LINES)

        return db.withTransaction {
            val branchId = db.branchDao().activeBranchId()
            val purchaseId = java.util.UUID.randomUUID().toString()
            val lines = mutableListOf<PurchaseLineEntity>()

            for (item in items) {
                val product = productDao.getById(item.productId) ?: throw PurchaseException(PurchaseException.Reason.PRODUCT_NOT_FOUND)
                if (item.qtyMilli <= 0) throw PurchaseException(PurchaseException.Reason.INVALID_QTY, product.name)
                if (item.unitCostPaise < 0) throw PurchaseException(PurchaseException.Reason.INVALID_COST, product.name)
                if (product.trackExpiry && item.expiry == null) throw PurchaseException(PurchaseException.Reason.EXPIRY_REQUIRED, product.name)
                val serials = item.serialNumbers.map { it.trim() }.filter { it.isNotEmpty() }
                if (product.serialTracked) {
                    if (item.qtyMilli % 1_000L != 0L || serials.size.toLong() != item.qtyMilli / 1_000L) {
                        throw PurchaseException(PurchaseException.Reason.SERIAL_COUNT, product.name)
                    }
                    if (serials.distinct().size != serials.size || serials.any { serialNumberDao.exists(it) }) {
                        throw PurchaseException(PurchaseException.Reason.SERIAL_DUPLICATE, product.name)
                    }
                } else if (serials.isNotEmpty()) {
                    throw PurchaseException(PurchaseException.Reason.SERIAL_COUNT, product.name)
                }

                val batch = BatchEntity(
                    productId = product.id, branchId = branchId, batchNo = item.batchNo?.trim()?.ifEmpty { null }, expiryDate = item.expiry,
                    costPaise = item.unitCostPaise, qtyReceivedMilli = item.qtyMilli, qtyOnHandMilli = item.qtyMilli,
                    receivedAt = purchaseDateMillis,
                )
                batchDao.insert(batch)
                if (serials.isNotEmpty()) {
                    serialNumberDao.insertAll(
                        serials.map { serial ->
                            SerialNumberEntity(productId = product.id, batchId = batch.id, serialNumber = serial)
                        },
                    )
                }
                stockDao.insert(
                    StockMovementEntity(
                        productId = product.id, branchId = branchId, batchId = batch.id, type = MovementType.PURCHASE, qtyMilli = item.qtyMilli,
                        unitCostPaise = item.unitCostPaise, refType = "PURCHASE", refId = purchaseId, createdAt = now,
                    )
                )
                if (updateProductCost && product.costPaise != item.unitCostPaise)
                    productDao.update(product.copy(costPaise = item.unitCostPaise, updatedAt = now, dirty = true))

                lines += PurchaseLineEntity(
                    purchaseId = purchaseId, productId = product.id, batchId = batch.id, nameSnapshot = product.name,
                    qtyMilli = item.qtyMilli, unitCostPaise = item.unitCostPaise,
                    lineTotalPaise = Money(item.unitCostPaise).timesQtyMilli(item.qtyMilli).paise,
                )
            }

            val total = billTotalPaise ?: lines.sumOf { it.lineTotalPaise }
            if (total < 0) throw PurchaseException(PurchaseException.Reason.INVALID_TOTAL)
            if (paidPaise < 0 || paidPaise > total) throw PurchaseException(PurchaseException.Reason.PAID_EXCEEDS_TOTAL)
            if (total - paidPaise > 0 && supplierId == null) throw PurchaseException(PurchaseException.Reason.SUPPLIER_REQUIRED_FOR_CREDIT)

            purchaseDao.insert(
                PurchaseEntity(
                    id = purchaseId, branchId = branchId, supplierId = supplierId, invoiceNo = invoiceNo?.trim()?.ifEmpty { null },
                    purchaseDate = purchaseDateMillis, totalPaise = total, paidPaise = paidPaise,
                    payMethod = if (paidPaise > 0) payMethod ?: PaymentMethod.CASH else null,
                    notes = notes?.trim()?.ifEmpty { null }, createdAt = now,
                )
            )
            purchaseDao.insertLines(lines)

            if (supplierId != null) {
                supplierDao.insertLedger(
                    SupplierLedgerEntity(supplierId = supplierId, type = SupplierLedgerType.PURCHASE, amountPaise = total,
                        refType = "PURCHASE", refId = purchaseId, createdAt = now)
                )
                if (paidPaise > 0) supplierDao.insertLedger(
                    SupplierLedgerEntity(supplierId = supplierId, type = SupplierLedgerType.PAYMENT, amountPaise = -paidPaise,
                        method = payMethod ?: PaymentMethod.CASH, refType = "PURCHASE", refId = purchaseId, createdAt = now)
                )
            }
            val who = supplierId?.let { supplierDao.getById(it)?.name } ?: "cash purchase"
            audit.log(AuditAction.PURCHASE, "Purchase ${Money(total).format()} from $who (${items.size} items)", "PURCHASE", purchaseId)
            purchaseId
        }
    }
}
