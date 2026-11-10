package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageModeRouter
import com.xcloak.storevia.domain.sale.CartItem
import com.xcloak.storevia.domain.sale.PaymentInput
import com.xcloak.storevia.domain.sale.RefundMode
import com.xcloak.storevia.domain.sale.ReturnItem
import com.xcloak.storevia.domain.sale.SalePreview
import com.xcloak.storevia.domain.sale.SaleTotals
import com.xcloak.storevia.domain.sale.ReturnPreview
import com.xcloak.storevia.domain.sale.SalesInventoryOperations
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.SaleEntity
import com.xcloak.storevia.core.database.entity.SaleLineEntity
import com.xcloak.storevia.core.database.entity.SaleStatus
import com.xcloak.storevia.core.database.entity.PaymentEntity
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SerialNumberEntity
import com.xcloak.storevia.core.database.entity.SerialNumberStatus
import com.xcloak.storevia.core.database.entity.StoreEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.longOrNull
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import javax.inject.Singleton

class CloudSalesInventoryException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

data class CloudReturnLine(
    val productId: String,
    val name: String,
    val unitSymbol: String,
    val allowDecimal: Boolean,
    val soldMilli: Long,
    val returnedMilli: Long,
)

data class CloudReturnDetail(val sale: SaleEntity, val lines: List<CloudReturnLine>)
data class CloudReceiptSnapshot(
    val store: StoreEntity,
    val sale: SaleEntity,
    val lines: List<SaleLineEntity>,
    val payments: List<PaymentEntity>,
    val customerName: String?,
    val serialNumbers: List<SerialNumberEntity>,
)

/** Executes business mutations through transactional Postgres RPCs; it never reads or writes Room. */
@Singleton
class CloudSalesInventoryRepository @Inject constructor(
    private val client: SupabaseClient,
    private val prefs: AppPrefs,
) : SalesInventoryOperations {
    override
    fun isCloudMode(): Boolean = StorageModeRouter.route(
        prefs.settings.value.storageMode,
        local = { false },
        cloud = { true },
    )

    fun observeRecentSales(branchId: String): Flow<List<SaleEntity>> = flow {
        while (true) {
            val storeId = requireStoreId("Loading cloud sales")
            val rows = try {
                client.from("sales").select {
                    filter {
                        eq("store_id", storeId)
                        eq("branch_id", branchId)
                    }
                    order("created_at", Order.DESCENDING)
                    limit(200)
                }.decodeList<JsonObject>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CloudSalesInventoryException("Loading cloud sales failed: ${e.message ?: "Supabase request failed."}", e)
            }
            emit(rows.map(::saleFrom))
            delay(3_000)
        }
    }

    suspend fun loadReturnDetail(saleId: String): CloudReturnDetail? {
        val storeId = requireStoreId("Loading cloud return")
        return routeCloud("Loading cloud return") {
            try {
                val sale = client.from("sales").select {
                    filter { eq("store_id", storeId); eq("id", saleId) }
                    limit(1)
                }.decodeList<JsonObject>().firstOrNull()?.let(::saleFrom) ?: return@routeCloud null
                val lineRows = client.from("sale_lines").select {
                    filter { eq("store_id", storeId); eq("sale_id", saleId) }
                    order("id", Order.ASCENDING)
                }.decodeList<JsonObject>()
                val products = client.from("products").select {
                    filter { eq("store_id", storeId) }
                }.decodeList<JsonObject>().associateBy { it.text("id") }
                val units = client.from("units").select {
                    filter { eq("store_id", storeId) }
                }.decodeList<JsonObject>().associateBy { it.text("id") }
                val lines = lineRows.groupBy { it.text("product_id") }.map { (productId, rows) ->
                    val product = products[productId]
                    val unit = product?.nullableText("unit_id")?.let(units::get)
                    CloudReturnLine(
                        productId = productId,
                        name = rows.first().text("name_snapshot"),
                        unitSymbol = unit?.text("symbol").orEmpty(),
                        allowDecimal = unit?.number("allow_decimal") != 0L,
                        soldMilli = rows.sumOf { it.number("qty_milli") },
                        returnedMilli = rows.sumOf { it.number("returned_qty_milli") },
                    )
                }
                CloudReturnDetail(sale, lines)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudSalesInventoryException) {
                throw e
            } catch (e: Exception) {
                throw CloudSalesInventoryException("Loading cloud return failed: ${e.message ?: "Supabase request failed."}", e)
            }
        }
    }

    fun observeReceipt(saleId: String): Flow<CloudReceiptSnapshot?> = flow {
        while (true) {
            val storeId = requireStoreId("Loading cloud receipt")
            try {
                val store = client.from("store").select {
                    filter { eq("store_id", storeId) }
                    limit(1)
                }.decodeList<JsonObject>().firstOrNull()?.let(::storeFrom)
                val sale = client.from("sales").select {
                    filter { eq("store_id", storeId); eq("id", saleId) }
                    limit(1)
                }.decodeList<JsonObject>().firstOrNull()?.let(::saleFrom)
                val snapshot = if (store == null || sale == null) null else {
                    val lines = client.from("sale_lines").select {
                        filter { eq("store_id", storeId); eq("sale_id", saleId) }
                        order("id", Order.ASCENDING)
                    }.decodeList<JsonObject>().map(::saleLineFrom)
                    val payments = client.from("payments").select {
                        filter { eq("store_id", storeId); eq("sale_id", saleId) }
                        order("created_at", Order.ASCENDING)
                    }.decodeList<JsonObject>().map(::paymentFrom)
                    val customerName = sale.customerId?.let { customerId ->
                        client.from("customers").select {
                            filter { eq("store_id", storeId); eq("id", customerId) }
                            limit(1)
                        }.decodeList<JsonObject>().firstOrNull()?.text("name")
                    }
                    val lineIds = lines.mapTo(mutableSetOf()) { it.id }
                    val serialNumbers = if (lineIds.isEmpty()) emptyList() else {
                        client.from("serial_numbers").select {
                            filter { eq("store_id", storeId) }
                        }.decodeList<JsonObject>()
                            .filter { it.nullableText("sale_line_id") in lineIds && it.nullableNumber("deleted_at") == null }
                            .map(::serialNumberFrom)
                    }
                    CloudReceiptSnapshot(store, sale, lines, payments, customerName, serialNumbers)
                }
                emit(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudSalesInventoryException) {
                throw e
            } catch (e: Exception) {
                throw CloudSalesInventoryException("Loading cloud receipt failed: ${e.message ?: "Supabase request failed."}", e)
            }
            delay(3_000)
        }
    }

    override suspend fun checkout(
        items: List<CartItem>,
        payments: List<PaymentInput>,
        customerId: String?,
        interState: Boolean,
        billDiscountPaise: Long,
        now: Long,
    ): String = call("storevia_checkout", "Completing cloud sale") { storeId ->
        checkoutArguments(
            storeId, java.util.UUID.randomUUID().toString(), items, payments,
            customerId, interState, billDiscountPaise, now,
        )
    }

    override suspend fun previewSale(
        items: List<CartItem>,
        interState: Boolean,
        billDiscountPaise: Long,
        now: Long,
    ): SalePreview {
        val storeId = requireStoreId("Loading cloud sale preview")
        return routeCloud("Loading cloud sale preview") {
            try {
                val response = client.postgrest.rpc(
                    "storevia_quote_sale",
                    checkoutArguments(
                        storeId, java.util.UUID.randomUUID().toString(), items, emptyList(),
                        null, interState, billDiscountPaise, now, quote = true,
                    ),
                ).decodeAs<JsonObject>()
                fun amount(key: String) = response[key]?.jsonPrimitive?.longOrNull ?: 0L
                SalePreview(
                    totals = SaleTotals(
                        subtotalPaise = amount("subtotal_paise"),
                        discountPaise = amount("discount_paise"),
                        taxPaise = amount("tax_paise"),
                        roundOffPaise = amount("round_off_paise"),
                        totalPaise = amount("total_paise"),
                    ),
                    lines = emptyList(),
                    soldAgainstNegativeStock =
                        response["sold_against_negative_stock"]?.jsonPrimitive?.content == "true",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CloudSalesInventoryException("Loading cloud sale preview failed: ${e.message ?: "Supabase request failed."}", e)
            }
        }
    }

    override suspend fun returnItems(
        saleId: String,
        items: List<ReturnItem>,
        refund: RefundMode,
        now: Long,
    ): String = call("storevia_return_sale", "Recording cloud return") { storeId ->
        buildJsonObject {
            put("p_store_id", storeId)
            put("p_sale_id", saleId)
            put("p_request_id", java.util.UUID.randomUUID().toString())
            put("p_payload", buildJsonObject {
                put("created_at", now)
                put("refund_mode", if (refund is RefundMode.KhataCredit) "KHATA_CREDIT" else "MONEY")
                if (refund is RefundMode.Money) put("refund_method", refund.method.name)
                putJsonArray("items") {
                    items.forEach { item ->
                        add(buildJsonObject {
                            put("product_id", item.productId)
                            put("qty_milli", item.qtyMilli)
                        })
                    }
                }
            })
        }
    }

    override suspend fun previewReturn(saleId: String, items: List<ReturnItem>): ReturnPreview {
        val storeId = requireStoreId("Loading cloud return preview")
        return routeCloud("Loading cloud return preview") {
            try {
                val response = client.postgrest.rpc(
                    "storevia_quote_return",
                    buildJsonObject {
                        put("p_store_id", storeId)
                        put("p_sale_id", saleId)
                        putJsonArray("p_items") {
                            items.forEach { item ->
                                add(buildJsonObject {
                                    put("product_id", item.productId)
                                    put("qty_milli", item.qtyMilli)
                                })
                            }
                        }
                    },
                ).decodeAs<JsonObject>()
                fun amount(key: String) = response[key]?.jsonPrimitive?.longOrNull ?: 0L
                ReturnPreview(
                    totalPaise = amount("total_paise"),
                    dueReducedPaise = amount("due_reduced_paise"),
                    refundPaise = amount("refund_paise"),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CloudSalesInventoryException("Loading cloud return preview failed: ${e.message ?: "Supabase request failed."}", e)
            }
        }
    }

    override suspend fun cancelSale(saleId: String, reason: String?, now: Long): String =
        call("storevia_cancel_sale", "Cancelling cloud sale") { storeId ->
            buildJsonObject {
                put("p_store_id", storeId)
                put("p_sale_id", saleId)
                put("p_request_id", java.util.UUID.randomUUID().toString())
                put("p_reason", reason.orEmpty())
                put("p_created_at", now)
            }
        }

    override suspend fun adjustStock(
        batchId: String,
        type: MovementType,
        qtyMilli: Long,
        note: String?,
        now: Long,
    ): String = call("storevia_adjust_stock", "Adjusting cloud stock") { storeId ->
        buildJsonObject {
            put("p_store_id", storeId)
            put("p_request_id", java.util.UUID.randomUUID().toString())
            put("p_batch_id", batchId)
            put("p_type", type.name)
            put("p_qty_milli", qtyMilli)
            put("p_note", note.orEmpty())
            put("p_created_at", now)
        }
    }

    private suspend fun call(
        rpc: String,
        operation: String,
        arguments: (String) -> JsonObject,
    ): String {
        val storeId = requireStoreId(operation)
        return routeCloud(operation) {
            try {
                client.postgrest.rpc(rpc, arguments(storeId)).decodeAs<String>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudSalesInventoryException) {
                throw e
            } catch (e: Exception) {
                throw CloudSalesInventoryException("$operation failed. No local changes were made: ${e.message ?: "Supabase request failed."}", e)
            }
        }
    }

    private suspend fun <T> routeCloud(operation: String, cloud: suspend () -> T): T =
        StorageModeRouter.routeSuspend(
            prefs.settings.value.storageMode,
            local = { throw CloudSalesInventoryException("$operation requires Cloud mode.") },
            cloud = cloud,
        )

    private fun requireStoreId(operation: String): String =
        prefs.settings.value.cloudStoreId?.takeIf(String::isNotBlank)
            ?: throw CloudSalesInventoryException("$operation failed: no cloud store is connected.")

    private fun saleFrom(row: JsonObject) = SaleEntity(
        id = row.text("id"),
        invoiceNo = row.text("invoice_no"),
        branchId = row.text("branch_id"),
        customerId = row.nullableText("customer_id"),
        status = runCatching { SaleStatus.valueOf(row.text("status")) }.getOrDefault(SaleStatus.COMPLETED),
        interState = row.number("inter_state") != 0L,
        subtotalPaise = row.number("subtotal_paise"),
        discountPaise = row.number("discount_paise"),
        taxPaise = row.number("tax_paise"),
        roundOffPaise = row.number("round_off_paise"),
        totalPaise = row.number("total_paise"),
        paidPaise = row.number("paid_paise"),
        returnedPaise = row.number("returned_paise"),
        cancelReason = row.nullableText("cancel_reason"),
        cancelledAt = row.nullableNumber("cancelled_at"),
        notes = row.nullableText("notes"),
        createdAt = row.number("created_at"),
        dirty = false,
    )

    private fun storeFrom(row: JsonObject) = StoreEntity(
        id = row.text("id"),
        name = row.text("name"),
        ownerName = row.text("owner_name"),
        address = row.text("address"),
        city = row.text("city"),
        phone = row.text("phone"),
        gstin = row.nullableText("gstin"),
        stateCode = row.text("state_code"),
        invoicePrefix = row.text("invoice_prefix"),
        allowNegativeStock = row.number("allow_negative_stock") != 0L,
        createdAt = row.number("created_at"),
        updatedAt = row.number("updated_at"),
        dirty = false,
    )

    private fun saleLineFrom(row: JsonObject) = SaleLineEntity(
        id = row.text("id"),
        saleId = row.text("sale_id"),
        productId = row.text("product_id"),
        batchId = row.nullableText("batch_id"),
        nameSnapshot = row.text("name_snapshot"),
        hsnSnapshot = row.nullableText("hsn_snapshot"),
        qtyMilli = row.number("qty_milli"),
        unitPricePaise = row.number("unit_price_paise"),
        discountPaise = row.number("discount_paise"),
        gstRateBp = row.number("gst_rate_bp").toInt(),
        taxInclusive = row.number("tax_inclusive") != 0L,
        taxableValuePaise = row.number("taxable_value_paise"),
        cgstPaise = row.number("cgst_paise"),
        sgstPaise = row.number("sgst_paise"),
        igstPaise = row.number("igst_paise"),
        lineTotalPaise = row.number("line_total_paise"),
        costSnapshotPaise = row.number("cost_snapshot_paise"),
        returnedQtyMilli = row.number("returned_qty_milli"),
        returnedAmountPaise = row.number("returned_amount_paise"),
        dirty = false,
    )

    private fun paymentFrom(row: JsonObject) = PaymentEntity(
        id = row.text("id"),
        saleId = row.nullableText("sale_id"),
        customerId = row.nullableText("customer_id"),
        method = runCatching { PaymentMethod.valueOf(row.text("method")) }.getOrDefault(PaymentMethod.OTHER),
        amountPaise = row.number("amount_paise"),
        reference = row.nullableText("reference"),
        createdAt = row.number("created_at"),
        dirty = false,
    )

    private fun serialNumberFrom(row: JsonObject) = SerialNumberEntity(
        id = row.text("id"),
        productId = row.text("product_id"),
        batchId = row.text("batch_id"),
        serialNumber = row.text("serial_number"),
        status = runCatching { SerialNumberStatus.valueOf(row.text("status")) }.getOrDefault(SerialNumberStatus.AVAILABLE),
        saleLineId = row.nullableText("sale_line_id"),
        updatedAt = row.number("updated_at"),
        deletedAt = row.nullableNumber("deleted_at"),
        dirty = false,
    )

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" }.orEmpty()

    private fun JsonObject.nullableText(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" }

    private fun JsonObject.number(key: String): Long = nullableNumber(key) ?: 0L

    private fun JsonObject.nullableNumber(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun checkoutArguments(
        storeId: String,
        requestId: String,
        items: List<CartItem>,
        payments: List<PaymentInput>,
        customerId: String?,
        interState: Boolean,
        billDiscountPaise: Long,
        now: Long,
        quote: Boolean = false,
    ) = buildJsonObject {
        put("p_store_id", storeId)
        put("p_request_id", requestId)
        put("p_payload", buildJsonObject {
            put("customer_id", customerId)
            put("inter_state", interState)
            put("bill_discount_paise", billDiscountPaise)
            put("created_at", now)
            if (quote) put("quote", true)
            putJsonArray("items") {
                items.forEach { item ->
                    add(buildJsonObject {
                        put("product_id", item.productId)
                        put("qty_milli", item.qtyMilli)
                        put("unit_price_paise", item.unitPricePaise)
                        put("discount_paise", item.discountPaise)
                        item.preferredBatchId?.let { put("preferred_batch_id", it) }
                    })
                }
            }
            putJsonArray("payments") {
                payments.forEach { payment ->
                    add(buildJsonObject {
                        put("method", payment.method.name)
                        put("amount_paise", payment.amountPaise)
                        payment.reference?.let { put("reference", it) }
                    })
                }
            }
        })
    }
}
