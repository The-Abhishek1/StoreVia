package com.xcloak.storevia.domain.customer

import com.xcloak.storevia.core.database.entity.PaymentMethod

interface CustomerPaymentOperations {
    fun isCloudMode(): Boolean
    suspend fun receive(customerId: String, amountPaise: Long, method: PaymentMethod, now: Long)
}

object LocalCustomerPaymentOperations : CustomerPaymentOperations {
    override fun isCloudMode() = false
    override suspend fun receive(customerId: String, amountPaise: Long, method: PaymentMethod, now: Long) {
        error("Cloud customer payment operation requested while in Local mode.")
    }
}

class CloudCustomerPaymentException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
