package com.xcloak.storevia.domain.purchase

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.SupplierDao
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierLedgerEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import javax.inject.Inject

class SupplierPaymentException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { INVALID_AMOUNT, EXCEEDS_BALANCE }
}

/** Records money paid to a supplier against what you owe. You can't pay more than you owe. */
class PaySupplierUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val supplierDao: SupplierDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
) {
    suspend operator fun invoke(supplierId: String, amountPaise: Long, method: PaymentMethod, now: Long = System.currentTimeMillis()) {
        access.require(Permission.MANAGE_PURCHASES)
        if (amountPaise <= 0) throw SupplierPaymentException(SupplierPaymentException.Reason.INVALID_AMOUNT)
        db.withTransaction {
            if (amountPaise > supplierDao.balance(supplierId)) throw SupplierPaymentException(SupplierPaymentException.Reason.EXCEEDS_BALANCE)
            supplierDao.insertLedger(
                SupplierLedgerEntity(supplierId = supplierId, type = SupplierLedgerType.PAYMENT, amountPaise = -amountPaise,
                    method = method, refType = "PAYMENT", createdAt = now)
            )
            audit.log(AuditAction.SUPPLIER_PAYMENT, "Paid ${Money(amountPaise).format()} to ${supplierDao.getById(supplierId)?.name ?: "supplier"}", "SUPPLIER", supplierId)
        }
    }
}
