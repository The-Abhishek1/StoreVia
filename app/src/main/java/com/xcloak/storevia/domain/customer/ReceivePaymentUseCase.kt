package com.xcloak.storevia.domain.customer

import androidx.room.withTransaction
import com.xcloak.storevia.core.access.*
import com.xcloak.storevia.core.money.Money
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.CustomerDao
import com.xcloak.storevia.core.database.dao.SaleDao
import com.xcloak.storevia.core.database.entity.*
import javax.inject.Inject

class KhataException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { INVALID_AMOUNT, EXCEEDS_BALANCE, CUSTOMER_NOT_FOUND }
}

/**
 * Records money received against a customer's khata, in one transaction:
 * settles the oldest unpaid bills first (so each bill's Due tag stays truthful),
 * writes the payment rows, and reduces the customer's ledger balance.
 */
class ReceivePaymentUseCase @Inject constructor(
    private val db: StorviaDatabase,
    private val customerDao: CustomerDao,
    private val saleDao: SaleDao,
    private val access: Access = OwnerAccess,
    private val audit: AuditSink = NoAudit,
) {
    suspend operator fun invoke(
        customerId: String,
        amountPaise: Long,
        method: PaymentMethod,
        now: Long = System.currentTimeMillis(),
    ) {
        access.require(Permission.RECEIVE_KHATA_PAYMENT)
        if (amountPaise <= 0) throw KhataException(KhataException.Reason.INVALID_AMOUNT)
        db.withTransaction {
            if (customerDao.getById(customerId) == null) {
                throw KhataException(KhataException.Reason.CUSTOMER_NOT_FOUND)
            }
            if (amountPaise > customerDao.balance(customerId)) throw KhataException(KhataException.Reason.EXCEEDS_BALANCE)

            var left = amountPaise
            val payments = mutableListOf<PaymentEntity>()
            for (sale in saleDao.unpaidForCustomer(customerId)) {
                if (left == 0L) break
                val take = minOf(sale.totalPaise - sale.returnedPaise - sale.paidPaise, left)
                saleDao.addPaid(sale.id, take)
                payments += PaymentEntity(saleId = sale.id, customerId = customerId, method = method, amountPaise = take, createdAt = now)
                left -= take
            }
            // Money not tied to a bill (e.g. an "old balance" entered by hand).
            if (left > 0) payments += PaymentEntity(saleId = null, customerId = customerId, method = method, amountPaise = left, createdAt = now)

            saleDao.insertPayments(payments)
            customerDao.insertLedger(
                CustomerLedgerEntity(
                    customerId = customerId, type = LedgerType.PAYMENT_RECEIVED, amountPaise = -amountPaise,
                    refType = "PAYMENT", refId = payments.first().id, createdAt = now,
                )
            )
            audit.log(AuditAction.KHATA_PAYMENT, "Received ${Money(amountPaise).format()} from ${customerDao.getById(customerId)?.name ?: "customer"}", "CUSTOMER", customerId)
        }
    }
}
