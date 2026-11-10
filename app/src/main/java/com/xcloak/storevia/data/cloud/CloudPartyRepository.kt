package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.core.database.dao.CustomerBalanceRow
import com.xcloak.storevia.core.database.dao.SupplierBalanceRow
import com.xcloak.storevia.core.database.entity.CustomerEntity
import com.xcloak.storevia.core.database.entity.CustomerLedgerEntity
import com.xcloak.storevia.core.database.entity.LedgerType
import com.xcloak.storevia.core.database.entity.PaymentMethod
import com.xcloak.storevia.core.database.entity.SupplierEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerEntity
import com.xcloak.storevia.core.database.entity.SupplierLedgerType
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import javax.inject.Singleton
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.xcloak.storevia.data.prefs.StorageModeRouter
import com.xcloak.storevia.domain.customer.CustomerPaymentOperations
import com.xcloak.storevia.domain.customer.CloudCustomerPaymentException
import com.xcloak.storevia.domain.customer.KhataException
import javax.inject.Inject

/**
 * Store-scoped, server-authoritative reads and writes for customers and suppliers.
 * Cloud failures are propagated; this repository never consults Room.
 */
@Singleton
class CloudPartyRepository @Inject constructor(
    private val client: SupabaseClient,
    private val prefs: AppPrefs,
) : CustomerPaymentOperations {
    override fun isCloudMode(): Boolean = StorageModeRouter.route(
        prefs.settings.value.storageMode,
        local = { false },
        cloud = { true },
    )

    override suspend fun receive(customerId: String, amountPaise: Long, method: PaymentMethod, now: Long) {
        val storeId = requireStoreId()
        try {
            client.postgrest.rpc(
                "storevia_receive_customer_payment",
                buildJsonObject {
                    put("p_store_id", storeId)
                    put("p_customer_id", customerId)
                    put("p_amount_paise", amountPaise)
                    put("p_method", method.name)
                    put("p_request_id", java.util.UUID.randomUUID().toString())
                    put("p_created_at", now)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            when {
                message.contains("customer not found", ignoreCase = true) ->
                    throw KhataException(KhataException.Reason.CUSTOMER_NOT_FOUND)
                message.contains("exceeds balance", ignoreCase = true) ->
                    throw KhataException(KhataException.Reason.EXCEEDS_BALANCE)
                else -> throw CloudCustomerPaymentException(
                    "Receiving cloud payment failed: ${message.ifBlank { "Supabase request failed." }}",
                    e,
                )
            }
        }
    }

    fun observeCustomers(query: String): Flow<List<CustomerBalanceRow>> = polling {
        val storeId = requireStoreId()
        val customers = rows("customers", storeId).map(::customerFrom)
        val balances = rows("customer_ledger", storeId).groupBy { it.string("customer_id") }
            .mapValues { (_, entries) -> entries.sumOf { it.long("amount_paise") } }
        customers.asSequence().filter { it.deletedAt == null }
            .filter { query.isBlank() || it.name.contains(query, true) || it.phone.orEmpty().contains(query, true) }
            .map { CustomerBalanceRow(it.id, it.name, it.phone, balances[it.id].orZero()) }
            .sortedWith(compareByDescending<CustomerBalanceRow> { it.balancePaise }.thenBy { it.name.lowercase() })
            .toList()
    }

    fun observeCustomerEntities(): Flow<List<CustomerEntity>> = polling {
        rows("customers", requireStoreId())
            .map(::customerFrom)
            .filter { it.deletedAt == null && it.isActive }
            .sortedBy { it.name.lowercase() }
    }

    fun observeSuppliers(query: String): Flow<List<SupplierBalanceRow>> = polling {
        val storeId = requireStoreId()
        val suppliers = rows("suppliers", storeId).map(::supplierFrom)
        val balances = rows("supplier_ledger", storeId).groupBy { it.string("supplier_id") }
            .mapValues { (_, entries) -> entries.sumOf { it.long("amount_paise") } }
        suppliers.asSequence().filter { it.deletedAt == null }
            .filter { query.isBlank() || it.name.contains(query, true) || it.phone.orEmpty().contains(query, true) }
            .map { SupplierBalanceRow(it.id, it.name, it.phone, balances[it.id].orZero()) }
            .sortedWith(compareByDescending<SupplierBalanceRow> { it.balancePaise }.thenBy { it.name.lowercase() })
            .toList()
    }

    fun observeCustomer(id: String): Flow<Pair<CustomerEntity?, List<CustomerLedgerEntity>>> = polling {
        val storeId = requireStoreId()
        val customer = rows("customers", storeId).firstOrNull { it.string("id") == id }?.let(::customerFrom)
            ?.takeIf { it.deletedAt == null }
        val ledger = rows("customer_ledger", storeId).filter { it.string("customer_id") == id }
            .map(::customerLedgerFrom).sortedWith(compareBy<CustomerLedgerEntity> { it.createdAt }.thenBy { it.id })
        customer to ledger
    }

    suspend fun getCustomer(id: String): CustomerEntity? {
        val storeId = requireStoreId()
        return rows("customers", storeId).firstOrNull { it.string("id") == id }
            ?.let(::customerFrom)?.takeIf { it.deletedAt == null }
    }

    fun observeSupplier(id: String): Flow<Pair<SupplierEntity?, List<SupplierLedgerEntity>>> = polling {
        val storeId = requireStoreId()
        val supplier = rows("suppliers", storeId).firstOrNull { it.string("id") == id }?.let(::supplierFrom)
            ?.takeIf { it.deletedAt == null }
        val ledger = rows("supplier_ledger", storeId).filter { it.string("supplier_id") == id }
            .map(::supplierLedgerFrom).sortedWith(compareBy<SupplierLedgerEntity> { it.createdAt }.thenBy { it.id })
        supplier to ledger
    }

    suspend fun getSupplier(id: String): SupplierEntity? {
        val storeId = requireStoreId()
        return rows("suppliers", storeId).firstOrNull { it.string("id") == id }
            ?.let(::supplierFrom)?.takeIf { it.deletedAt == null }
    }

    fun observeTotalCustomerDue(): Flow<Long> = polling {
        val storeId = requireStoreId()
        val activeIds = rows("customers", storeId).filter { it.long("deleted_at").isNullOrZero() }
            .map { it.string("id") }.toSet()
        rows("customer_ledger", storeId).filter { it.string("customer_id") in activeIds }
            .groupBy { it.string("customer_id") }.values.sumOf { entries ->
                entries.sumOf { it.long("amount_paise") }.coerceAtLeast(0L)
            }
    }

    fun observeTotalSupplierPayable(): Flow<Long> = polling {
        val storeId = requireStoreId()
        val activeIds = rows("suppliers", storeId).filter { it.long("deleted_at").isNullOrZero() }
            .map { it.string("id") }.toSet()
        rows("supplier_ledger", storeId).filter { it.string("supplier_id") in activeIds }
            .groupBy { it.string("supplier_id") }.values.sumOf { entries ->
                entries.sumOf { it.long("amount_paise") }.coerceAtLeast(0L)
            }
    }

    suspend fun createCustomer(customer: CustomerEntity) {
        val storeId = requireStoreId()
        client.from("customers").insert(customer.toCloudJson(storeId))
    }

    suspend fun updateCustomer(customer: CustomerEntity) {
        val storeId = requireStoreId()
        client.from("customers").update(customer.toCloudJson(storeId)) {
            filter { eq("id", customer.id); eq("store_id", storeId) }
        }
    }

    suspend fun deleteCustomer(id: String, now: Long = System.currentTimeMillis()) {
        val storeId = requireStoreId()
        val row = rows("customers", storeId).firstOrNull { it.string("id") == id } ?: return
        val current = customerFrom(row)
        client.from("customers").update(current.copy(deletedAt = now).toCloudJson(storeId)) {
            filter { eq("id", id); eq("store_id", storeId) }
        }
    }

    suspend fun createSupplier(supplier: SupplierEntity) {
        val storeId = requireStoreId()
        client.from("suppliers").insert(supplier.toCloudJson(storeId))
    }

    suspend fun updateSupplier(supplier: SupplierEntity) {
        val storeId = requireStoreId()
        client.from("suppliers").update(supplier.toCloudJson(storeId)) {
            filter { eq("id", supplier.id); eq("store_id", storeId) }
        }
    }

    suspend fun deleteSupplier(id: String, now: Long = System.currentTimeMillis()) {
        val storeId = requireStoreId()
        val row = rows("suppliers", storeId).firstOrNull { it.string("id") == id } ?: return
        val current = supplierFrom(row)
        client.from("suppliers").update(current.copy(deletedAt = now).toCloudJson(storeId)) {
            filter { eq("id", id); eq("store_id", storeId) }
        }
    }

    suspend fun addCustomerLedger(entry: CustomerLedgerEntity) {
        val storeId = requireStoreId()
        client.from("customer_ledger").insert(entry.toCloudJson(storeId))
    }

    suspend fun addSupplierLedger(entry: SupplierLedgerEntity) {
        val storeId = requireStoreId()
        client.from("supplier_ledger").insert(entry.toCloudJson(storeId))
    }

    private fun requireStoreId(): String {
        val settings = prefs.settings.value
        check(settings.storageMode == StorageMode.CLOUD) { "Cloud party access requires CLOUD storage mode." }
        return checkNotNull(settings.cloudStoreId?.takeIf(String::isNotBlank)) {
            "Cloud party access requires a selected store."
        }
    }

    private suspend fun rows(table: String, storeId: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var offset = 0L
        while (true) {
            val page = client.from(table).select {
                filter { eq("store_id", storeId) }
                order("id", Order.ASCENDING)
                range(offset, offset + PAGE_SIZE - 1L)
            }.decodeList<JsonObject>()
            result += page
            if (page.size < PAGE_SIZE) return result
            offset += PAGE_SIZE
        }
    }

    private fun <T> polling(fetch: suspend () -> T): Flow<T> = flow {
        while (true) {
            emit(fetch())
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.longOrNull ?: 0L
    private fun JsonObject.longOrNull(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull
    private fun Long?.isNullOrZero(): Boolean = this == null || this == 0L
    private fun Long?.orZero(): Long = this ?: 0L

    private fun customerFrom(row: JsonObject) = CustomerEntity(
        id = row.string("id"), name = row.string("name"), phone = row.stringOrNull("phone"),
        creditLimitPaise = row.longOrNull("credit_limit_paise"), isActive = row.long("is_active") != 0L,
        createdAt = row.long("created_at"), updatedAt = row.long("updated_at"),
        deletedAt = row.longOrNull("deleted_at"), dirty = false,
    )

    private fun supplierFrom(row: JsonObject) = SupplierEntity(
        id = row.string("id"), name = row.string("name"), phone = row.stringOrNull("phone"),
        gstin = row.stringOrNull("gstin"), notes = row.stringOrNull("notes"),
        isActive = row.long("is_active") != 0L, createdAt = row.long("created_at"),
        updatedAt = row.long("updated_at"), deletedAt = row.longOrNull("deleted_at"), dirty = false,
    )

    private fun customerLedgerFrom(row: JsonObject) = CustomerLedgerEntity(
        id = row.string("id"), customerId = row.string("customer_id"), type = LedgerType.valueOf(row.string("type")),
        amountPaise = row.long("amount_paise"), refType = row.stringOrNull("ref_type"),
        refId = row.stringOrNull("ref_id"), createdAt = row.long("created_at"), dirty = false,
    )

    private fun supplierLedgerFrom(row: JsonObject) = SupplierLedgerEntity(
        id = row.string("id"), supplierId = row.string("supplier_id"),
        type = SupplierLedgerType.valueOf(row.string("type")), amountPaise = row.long("amount_paise"),
        method = row.stringOrNull("method")?.let { com.xcloak.storevia.core.database.entity.PaymentMethod.valueOf(it) },
        refType = row.stringOrNull("ref_type"), refId = row.stringOrNull("ref_id"),
        createdAt = row.long("created_at"), dirty = false,
    )

    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

    companion object {
        private const val PAGE_SIZE = 500
        private const val POLL_INTERVAL_MS = 5_000L
    }
}

internal fun CustomerEntity.toCloudJson(storeId: String) = buildJsonObject {
    put("id", id); put("name", name); putNullable("phone", phone)
    putNullable("credit_limit_paise", creditLimitPaise); put("is_active", if (isActive) 1 else 0)
    put("created_at", createdAt); put("updated_at", System.currentTimeMillis())
    putNullable("deleted_at", deletedAt); put("store_id", storeId)
}

internal fun SupplierEntity.toCloudJson(storeId: String) = buildJsonObject {
    put("id", id); put("name", name); putNullable("phone", phone); putNullable("gstin", gstin)
    putNullable("notes", notes); put("is_active", if (isActive) 1 else 0)
    put("created_at", createdAt); put("updated_at", System.currentTimeMillis())
    putNullable("deleted_at", deletedAt); put("store_id", storeId)
}

internal fun CustomerLedgerEntity.toCloudJson(storeId: String) = buildJsonObject {
    put("id", id); put("customer_id", customerId); put("type", type.name)
    put("amount_paise", amountPaise); putNullable("ref_type", refType); putNullable("ref_id", refId)
    put("created_at", createdAt); put("store_id", storeId)
}

internal fun SupplierLedgerEntity.toCloudJson(storeId: String) = buildJsonObject {
    put("id", id); put("supplier_id", supplierId); put("type", type.name)
    put("amount_paise", amountPaise); putNullable("method", method?.name)
    putNullable("ref_type", refType); putNullable("ref_id", refId)
    put("created_at", createdAt); put("store_id", storeId)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
    if (value == null) put(key, kotlinx.serialization.json.JsonNull) else put(key, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Long?) {
    if (value == null) put(key, kotlinx.serialization.json.JsonNull) else put(key, value)
}
