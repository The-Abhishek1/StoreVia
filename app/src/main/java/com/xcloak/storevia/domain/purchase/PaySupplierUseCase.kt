package com.xcloak.storevia.domain.purchase

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.data.cloud.CloudPurchaseRepository
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.PurchaseDao
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierLedgerEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import javax.inject.Inject

class SupplierPaymentException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        INVALID_AMOUNT, EXCEEDS_BALANCE, SUPPLIER_NOT_FOUND, PURCHASE_NOT_FOUND,
        PURCHASE_SUPPLIER_MISMATCH, EXCEEDS_PURCHASE_BALANCE,
    }
}

/** Records a payment either against one explicitly selected bill or against an unallocated supplier balance. */
class PaySupplierUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val supplierDao: SupplierDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
    private val purchaseDao: PurchaseDao = db.purchaseDao(),
    private val cloudPurchases: CloudPurchaseRepository? = null,
) {
    suspend operator fun invoke(
        supplierId: String,
        amountPaise: Long,
        method: PaymentMethod,
        now: Long = System.currentTimeMillis(),
        purchaseId: String? = null,
    ) {
        access.require(Permission.MANAGE_PURCHASES)
        if (amountPaise <= 0) throw SupplierPaymentException(SupplierPaymentException.Reason.INVALID_AMOUNT)
        cloudPurchases?.takeIf { it.isCloudMode() }?.let {
            it.paySupplier(supplierId, amountPaise, method, purchaseId, now)
            return
        }
        db.withTransaction {
            if (supplierDao.getById(supplierId) == null) {
                throw SupplierPaymentException(SupplierPaymentException.Reason.SUPPLIER_NOT_FOUND)
            }
            if (amountPaise > supplierDao.balance(supplierId)) {
                throw SupplierPaymentException(SupplierPaymentException.Reason.EXCEEDS_BALANCE)
            }

            val refType: String
            if (purchaseId == null) {
                if (amountPaise > supplierDao.unallocatedBalance(supplierId)) {
                    throw SupplierPaymentException(SupplierPaymentException.Reason.EXCEEDS_BALANCE)
                }
                refType = "SUPPLIER_BALANCE"
            } else {
                val purchase = purchaseDao.getById(purchaseId)
                    ?: throw SupplierPaymentException(SupplierPaymentException.Reason.PURCHASE_NOT_FOUND)
                if (purchase.supplierId != supplierId) {
                    throw SupplierPaymentException(SupplierPaymentException.Reason.PURCHASE_SUPPLIER_MISMATCH)
                }
                if (amountPaise > purchase.totalPaise - purchase.paidPaise) {
                    throw SupplierPaymentException(SupplierPaymentException.Reason.EXCEEDS_PURCHASE_BALANCE)
                }
                if (purchaseDao.addPayment(purchaseId, supplierId, amountPaise) != 1) {
                    throw SupplierPaymentException(SupplierPaymentException.Reason.EXCEEDS_PURCHASE_BALANCE)
                }
                refType = "PURCHASE"
            }
            supplierDao.insertLedger(
                SupplierLedgerEntity(supplierId = supplierId, type = SupplierLedgerType.PAYMENT, amountPaise = -amountPaise,
                    method = method, refType = refType, refId = purchaseId, createdAt = now)
            )
            audit.log(AuditAction.SUPPLIER_PAYMENT, "Paid ${Money(amountPaise).format()} to ${supplierDao.getById(supplierId)?.name ?: "supplier"}", "SUPPLIER", supplierId)
        }
    }
}
