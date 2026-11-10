package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.core.database.dao.PurchaseListRow
import com.xcloak.storevia.core.database.entity.MovementType
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.PurchaseEntity
import com.xcloak.storevia.core.database.entity.PurchaseLineEntity
import com.xcloak.storevia.core.database.entity.BatchEntity
import com.xcloak.storevia.core.database.entity.SerialNumberEntity
import com.xcloak.storevia.core.database.entity.SerialNumberStatus
import com.xcloak.storevia.core.database.entity.StockMovementEntity
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.database.dao.ReorderRow
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import com.xcloak.storevia.domain.purchase.PurchaseItem
import com.xcloak.storevia.domain.sale.SerialNumberValidationException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

class CloudPurchaseException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

data class CloudPurchaseDetail(
    val purchase: PurchaseEntity,
    val supplierName: String?,
    val lines: List<PurchaseLineEntity>,
)

/** Store-scoped purchase reads and atomic purchase/receiving/payment RPCs. */
@Singleton
class CloudPurchaseRepository @Inject constructor(
    private val client: SupabaseClient,
    private val prefs: AppPrefs,
) {
    fun isCloudMode(): Boolean = prefs.settings.value.storageMode == StorageMode.CLOUD

    fun observeBranchId(): Flow<String?> = polling {
        val active = rows("branches").filter { it.int("is_active") != 0 && it.isNull("deleted_at") }
        when (active.size) {
            1 -> active.single().string("id")
            0 -> null
            else -> throw CloudPurchaseException("Select one active cloud branch before purchasing or receiving stock.")
        }
    }

    fun observeSuppliers(): Flow<List<SupplierEntity>> = polling {
        rows("suppliers").filter { it.isNull("deleted_at") && it.int("is_active", 1) != 0 }
            .map(::supplierFrom).sortedBy { it.name.lowercase() }
    }

    suspend fun getSupplier(id: String): SupplierEntity? =
        rows("suppliers").firstOrNull { it.string("id") == id && it.isNull("deleted_at") }
            ?.let(::supplierFrom)

    fun observePurchases(
        branchId: String?,
        query: String,
        unpaidOnly: Boolean,
    ): Flow<List<PurchaseListRow>> = polling {
        if (branchId == null) return@polling emptyList()
        val suppliers = rows("suppliers").associate { it.string("id") to it.string("name") }
        rows("purchases").asSequence()
            .filter { it.string("branch_id") == branchId }
            .filter { query.isBlank() || it.stringOrNull("invoice_no").orEmpty().contains(query, true) ||
                suppliers[it.stringOrNull("supplier_id")].orEmpty().contains(query, true) }
            .filter { !unpaidOnly || it.long("paid_paise") < it.long("total_paise") }
            .sortedWith(compareByDescending<JsonObject> { it.long("purchase_date") }.thenByDescending { it.long("created_at") })
            .take(200)
            .map { row ->
                val supplierId = row.stringOrNull("supplier_id")
                PurchaseListRow(
                    id = row.string("id"),
                    invoiceNo = row.stringOrNull("invoice_no"),
                    supplierName = suppliers[supplierId],
                    purchaseDate = row.long("purchase_date"),
                    totalPaise = row.long("total_paise"),
                    paidPaise = row.long("paid_paise"),
                )
            }.toList()
    }

    fun observePurchaseDetail(id: String, branchId: String?): Flow<CloudPurchaseDetail?> = polling {
        if (branchId == null) return@polling null
        val purchase = rows("purchases").firstOrNull {
            it.string("id") == id && it.string("branch_id") == branchId
        }?.let(::purchaseFrom) ?: return@polling null
        val supplierName = purchase.supplierId?.let { supplierId ->
            rows("suppliers").firstOrNull { it.string("id") == supplierId }?.string("name")
        }
        val lines = rows("purchase_lines").filter { it.string("purchase_id") == id }
            .map(::purchaseLineFrom)
        CloudPurchaseDetail(purchase, supplierName, lines)
    }

    fun observeReorderRows(branchId: String?): Flow<List<ReorderRow>> = polling {
        if (branchId == null) return@polling emptyList()
        val products =         rows("products").filter { it.isNull("deleted_at") && it.int("is_active", 1) != 0 }
        val units = rows("units").associate { it.string("id") to it.string("symbol") }
        val decimalUnits = rows("units").associate { it.string("id") to (it.int("allow_decimal") != 0) }
        val stock = rows("batches").filter {
            it.string("branch_id") == branchId && it.isNull("deleted_at")
        }.groupBy { it.string("product_id") }.mapValues { (_, batches) -> batches.sumOf { it.long("qty_on_hand_milli") } }
        val parentsWithChildren = products.mapNotNull { it.stringOrNull("parent_product_id") }.toSet()
        products.asSequence()
            .filter { it.long("min_stock_milli") > 0 }
            .filter { it.stringOrNull("parent_product_id") != null || it.string("id") !in parentsWithChildren }
            .mapNotNull { product ->
                val onHand = stock[product.string("id")] ?: 0L
                if (onHand >= product.long("min_stock_milli")) null
                else ReorderRow(
                    productId = product.string("id"),
                    name = product.string("name"),
                    unitSymbol = units[product.string("unit_id")].orEmpty(),
                    allowDecimal = decimalUnits[product.string("unit_id")] ?: false,
                    onHandMilli = onHand,
                    minStockMilli = product.long("min_stock_milli"),
                    costPaise = product.long("cost_paise"),
                )
            }.sortedBy { it.name.lowercase() }.toList()
    }

    fun observeBatches(productId: String, branchId: String): Flow<List<BatchEntity>> = polling {
        rows("batches").filter {
            it.string("product_id") == productId && it.string("branch_id") == branchId && it.isNull("deleted_at")
        }.map(::batchFrom).sortedWith(compareBy<BatchEntity> { it.expiryDate }.thenByDescending { it.receivedAt })
    }

    fun observeStockMovements(productId: String, branchId: String, limit: Int): Flow<List<StockMovementEntity>> = polling {
        rows("stock_movements").filter {
            it.string("product_id") == productId && it.string("branch_id") == branchId
        }.map(::movementFrom).sortedByDescending { it.createdAt }.take(limit)
    }

    fun observeMovementCount(productId: String): Flow<Int> = polling {
        rows("stock_movements").count { it.string("product_id") == productId }
    }

    fun observeSerialNumbers(productId: String, branchId: String): Flow<List<SerialNumberEntity>> = polling {
        val batchIds = rows("batches").filter {
            it.string("product_id") == productId && it.string("branch_id") == branchId && it.isNull("deleted_at")
        }.map { it.string("id") }.toSet()
        rows("serial_numbers").filter {
            it.string("product_id") == productId && it.string("batch_id") in batchIds && it.isNull("deleted_at")
        }.map(::serialNumberFrom).sortedBy { it.serialNumber }
    }

    suspend fun storeName(): String = rows("store").firstOrNull()?.string("name").orEmpty()

    suspend fun recordPurchase(
        items: List<PurchaseItem>,
        supplierId: String?,
        invoiceNo: String?,
        purchaseDateMillis: Long,
        billTotalPaise: Long?,
        paidPaise: Long,
        payMethod: PaymentMethod?,
        notes: String?,
        updateProductCost: Boolean,
        now: Long,
    ): String {
        val store = storeId()
        val branch = branchId()
        return rpc("storevia_record_purchase", "Recording cloud purchase", store) {
        buildJsonObject {
            put("p_store_id", store)
            put("p_request_id", java.util.UUID.randomUUID().toString())
            put("p_payload", buildJsonObject {
                put("branch_id", branch)
                put("supplier_id", supplierId)
                put("invoice_no", invoiceNo.orEmpty())
                put("purchase_date", purchaseDateMillis)
                billTotalPaise?.let { put("bill_total_paise", it) }
                put("paid_paise", paidPaise)
                put("pay_method", payMethod?.name.orEmpty())
                put("notes", notes.orEmpty())
                put("update_product_cost", updateProductCost)
                put("created_at", now)
                putJsonArray("items") {
                    items.forEach { item ->
                        add(buildJsonObject {
                            put("product_id", item.productId)
                            put("qty_milli", item.qtyMilli)
                            put("unit_cost_paise", item.unitCostPaise)
                            put("batch_no", item.batchNo.orEmpty())
                            item.expiry?.let { put("expiry_date", it.toString()) }
                            putJsonArray("serial_numbers") { item.serialNumbers.forEach { add(JsonPrimitive(it)) } }
                        })
                    }
                    }
            })
        }
    }
    }

    suspend fun editPurchase(
        purchaseId: String,
        invoiceNo: String?,
        purchaseDateMillis: Long,
        totalPaise: Long,
        notes: String?,
        now: Long,
    ): String {
        val store = storeId()
        return rpc("storevia_edit_purchase", "Editing cloud purchase", store) {
        buildJsonObject {
            put("p_store_id", store)
            put("p_request_id", java.util.UUID.randomUUID().toString())
            put("p_purchase_id", purchaseId)
            put("p_payload", buildJsonObject {
                put("invoice_no", invoiceNo.orEmpty())
                put("purchase_date", purchaseDateMillis)
                put("total_paise", totalPaise)
                put("notes", notes.orEmpty())
                put("updated_at", now)
            })
        }
        }
    }

    suspend fun receiveStock(
        productId: String,
        qtyMilli: Long,
        costPaise: Long,
        batchNo: String?,
        expiryDate: java.time.LocalDate?,
        mrpPaise: Long?,
        type: MovementType,
        serialNumbers: List<String>,
        now: Long = System.currentTimeMillis(),
    ): String {
        val store = storeId()
        val branch = branchId()
        return try {
            rpc("storevia_receive_stock", "Receiving cloud stock", store) {
                buildJsonObject {
                    put("p_store_id", store)
                    put("p_request_id", java.util.UUID.randomUUID().toString())
                    put("p_payload", buildJsonObject {
                        put("branch_id", branch)
                        put("product_id", productId)
                        put("qty_milli", qtyMilli)
                        put("cost_paise", costPaise)
                        put("batch_no", batchNo.orEmpty())
                        expiryDate?.let { put("expiry_date", it.toString()) }
                        mrpPaise?.let { put("mrp_paise", it) }
                        put("type", type.name)
                        put("created_at", now)
                        putJsonArray("serial_numbers") { serialNumbers.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            }
        } catch (e: CloudPurchaseException) {
            when {
                e.message?.contains("Serial count", ignoreCase = true) == true ->
                    throw SerialNumberValidationException(SerialNumberValidationException.Reason.COUNT_MISMATCH)
                e.message?.contains("Duplicate serial", ignoreCase = true) == true ->
                    throw SerialNumberValidationException(SerialNumberValidationException.Reason.DUPLICATE)
                e.message?.contains("untracked product", ignoreCase = true) == true ->
                    throw SerialNumberValidationException(SerialNumberValidationException.Reason.PRODUCT_NOT_TRACKED)
                else -> throw e
            }
        }
    }

    suspend fun paySupplier(
        supplierId: String,
        amountPaise: Long,
        method: PaymentMethod,
        purchaseId: String?,
        now: Long,
    ): String {
        val store = storeId()
        return rpc("storevia_pay_supplier", "Paying cloud supplier", store) {
        buildJsonObject {
            put("p_store_id", store)
            put("p_request_id", java.util.UUID.randomUUID().toString())
            put("p_payload", buildJsonObject {
                put("supplier_id", supplierId)
                put("amount_paise", amountPaise)
                put("method", method.name)
                put("purchase_id", purchaseId)
                put("created_at", now)
            })
        }
        }
    }

    private suspend fun branchId(): String {
        val branches = rows("branches").filter { it.int("is_active") != 0 && it.isNull("deleted_at") }
        return branches.singleOrNull()?.string("id")
            ?: throw CloudPurchaseException("Purchasing requires exactly one active cloud branch.")
    }

    private suspend fun storeId(): String {
        check(isCloudMode()) { "Cloud purchase access requires CLOUD storage mode." }
        return checkNotNull(prefs.settings.value.cloudStoreId?.takeIf(String::isNotBlank)) {
            "Cloud purchase access requires a selected store."
        }
    }

    private suspend fun rows(table: String): List<JsonObject> {
        val store = storeId()
        return client.from(table).select {
            filter { eq("store_id", store) }
        }.decodeList()
    }

    private suspend fun rpc(name: String, operation: String, store: String, args: () -> JsonObject): String {
        return try {
            client.postgrest.rpc(name, args()).decodeAs<String>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CloudPurchaseException("$operation failed. No local changes were made: ${e.message ?: "Supabase request failed."}", e)
        }
    }

    private fun <T> polling(load: suspend () -> T): Flow<T> = flow {
        while (true) {
            emit(load())
            delay(2_000)
        }
    }

    private fun supplierFrom(row: JsonObject) = SupplierEntity(
        id = row.string("id"),
        name = row.string("name"),
        phone = row.stringOrNull("phone"),
        gstin = row.stringOrNull("gstin"),
        notes = row.stringOrNull("notes"),
        isActive = row.int("is_active", 1) != 0,
        createdAt = row.long("created_at"),
        updatedAt = row.long("updated_at"),
        deletedAt = row.long("deleted_at"),
        dirty = false,
    )

    private fun purchaseFrom(row: JsonObject) = PurchaseEntity(
        id = row.string("id"),
        branchId = row.string("branch_id"),
        supplierId = row.stringOrNull("supplier_id"),
        invoiceNo = row.stringOrNull("invoice_no"),
        purchaseDate = row.long("purchase_date"),
        totalPaise = row.long("total_paise"),
        paidPaise = row.long("paid_paise"),
        payMethod = row.stringOrNull("pay_method")?.let { runCatching { PaymentMethod.valueOf(it) }.getOrNull() },
        notes = row.stringOrNull("notes"),
        createdAt = row.long("created_at"),
        dirty = false,
    )

    private fun purchaseLineFrom(row: JsonObject) = PurchaseLineEntity(
        id = row.string("id"),
        purchaseId = row.string("purchase_id"),
        productId = row.string("product_id"),
        batchId = row.string("batch_id"),
        nameSnapshot = row.string("name_snapshot"),
        qtyMilli = row.long("qty_milli"),
        unitCostPaise = row.long("unit_cost_paise"),
        lineTotalPaise = row.long("line_total_paise"),
        dirty = false,
    )

    private fun batchFrom(row: JsonObject) = BatchEntity(
        id = row.string("id"),
        productId = row.string("product_id"),
        branchId = row.string("branch_id"),
        batchNo = row.stringOrNull("batch_no"),
        expiryDate = row.stringOrNull("expiry_date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
        costPaise = row.long("cost_paise"),
        mrpPaise = row.longOrNull("mrp_paise"),
        qtyReceivedMilli = row.long("qty_received_milli"),
        qtyOnHandMilli = row.long("qty_on_hand_milli"),
        receivedAt = row.long("received_at"),
        updatedAt = row.long("updated_at"),
        deletedAt = row.longOrNull("deleted_at"),
        dirty = false,
    )

    private fun movementFrom(row: JsonObject) = StockMovementEntity(
        id = row.string("id"),
        productId = row.string("product_id"),
        branchId = row.string("branch_id"),
        batchId = row.stringOrNull("batch_id"),
        type = runCatching { MovementType.valueOf(row.string("type")) }.getOrDefault(MovementType.PURCHASE),
        qtyMilli = row.long("qty_milli"),
        unitCostPaise = row.longOrNull("unit_cost_paise"),
        refType = row.stringOrNull("ref_type"),
        refId = row.stringOrNull("ref_id"),
        reason = row.stringOrNull("reason"),
        createdAt = row.long("created_at"),
        dirty = false,
    )

    private fun serialNumberFrom(row: JsonObject) = SerialNumberEntity(
        id = row.string("id"),
        productId = row.string("product_id"),
        batchId = row.string("batch_id"),
        serialNumber = row.string("serial_number"),
        status = runCatching { SerialNumberStatus.valueOf(row.string("status")) }.getOrDefault(SerialNumberStatus.AVAILABLE),
        saleLineId = row.stringOrNull("sale_line_id"),
        updatedAt = row.long("updated_at"),
        deletedAt = row.longOrNull("deleted_at"),
        dirty = false,
    )

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.isNull(key: String): Boolean = this[key]?.jsonPrimitive?.contentOrNull == null
    private fun JsonObject.int(key: String, default: Int = 0): Int = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: default
}
