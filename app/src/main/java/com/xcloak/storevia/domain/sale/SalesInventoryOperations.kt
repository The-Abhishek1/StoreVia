package com.xcloak.storevia.domain.sale

import com.xcloak.storevia.core.database.entity.MovementType

interface SalesInventoryOperations {
    fun isCloudMode(): Boolean
    suspend fun checkout(
        items: List<CartItem>,
        payments: List<PaymentInput>,
        customerId: String?,
        interState: Boolean,
        billDiscountPaise: Long,
        now: Long,
    ): String
    suspend fun previewSale(
        items: List<CartItem>,
        interState: Boolean,
        billDiscountPaise: Long,
        now: Long,
    ): SalePreview
    suspend fun returnItems(saleId: String, items: List<ReturnItem>, refund: RefundMode, now: Long): String
    suspend fun previewReturn(saleId: String, items: List<ReturnItem>): ReturnPreview
    suspend fun cancelSale(saleId: String, reason: String?, now: Long): String
    suspend fun adjustStock(batchId: String, type: MovementType, qtyMilli: Long, note: String?, now: Long): String
}

object LocalSalesInventoryOperations : SalesInventoryOperations {
    override fun isCloudMode() = false
    override suspend fun checkout(items: List<CartItem>, payments: List<PaymentInput>, customerId: String?, interState: Boolean, billDiscountPaise: Long, now: Long): String =
        error("Cloud operations are not configured.")
    override suspend fun previewSale(items: List<CartItem>, interState: Boolean, billDiscountPaise: Long, now: Long): SalePreview =
        error("Cloud operations are not configured.")
    override suspend fun returnItems(saleId: String, items: List<ReturnItem>, refund: RefundMode, now: Long): String =
        error("Cloud operations are not configured.")
    override suspend fun previewReturn(saleId: String, items: List<ReturnItem>): ReturnPreview =
        error("Cloud operations are not configured.")
    override suspend fun cancelSale(saleId: String, reason: String?, now: Long): String =
        error("Cloud operations are not configured.")
    override suspend fun adjustStock(batchId: String, type: MovementType, qtyMilli: Long, note: String?, now: Long): String =
        error("Cloud operations are not configured.")
}
