package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.core.database.entity.HeldCartEntity
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

class CloudHeldCartException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

@Singleton
class CloudHeldCartRepository @Inject constructor(
    private val client: SupabaseClient,
    private val prefs: AppPrefs,
) {
    fun observeAll(): Flow<List<HeldCartEntity>> = flow {
        while (true) {
            emit(loadAll())
            delay(3_000)
        }
    }

    suspend fun getById(id: String): HeldCartEntity? = cloudCall("Loading cloud held bill") {
        val storeId = requireStoreId()
        client.from("held_carts").select {
            filter {
                eq("store_id", storeId)
                eq("id", id)
            }
            limit(1)
        }.decodeList<JsonObject>().firstOrNull()
            ?.takeIf { it.isNull("deleted_at") }
            ?.toHeldCart()
    }

    suspend fun insert(cart: HeldCartEntity) = cloudCall("Saving cloud held bill") {
        val storeId = requireStoreId()
        client.from("held_carts").insert(
            buildJsonObject {
                put("id", cart.id)
                put("label", cart.label)
                put("customer_id", cart.customerId)
                put("inter_state", if (cart.interState) 1 else 0)
                put("bill_discount_paise", cart.billDiscountPaise)
                put("items_json", cart.itemsJson)
                put("item_count", cart.itemCount)
                put("created_at", cart.createdAt)
                put("updated_at", cart.updatedAt)
                put("deleted_at", cart.deletedAt)
                put("store_id", storeId)
            },
        )
    }

    suspend fun delete(id: String, now: Long) = cloudCall("Deleting cloud held bill") {
        val storeId = requireStoreId()
        client.from("held_carts").update(
            buildJsonObject {
                put("deleted_at", now)
                put("updated_at", now)
            },
        ) {
            filter {
                eq("store_id", storeId)
                eq("id", id)
            }
        }
    }

    private suspend fun loadAll(): List<HeldCartEntity> = cloudCall("Loading cloud held bills") {
        val storeId = requireStoreId()
        client.from("held_carts").select {
            filter {
                eq("store_id", storeId)
            }
            order("created_at", Order.DESCENDING)
        }.decodeList<JsonObject>().filter { it.isNull("deleted_at") }.map { it.toHeldCart() }
    }

    private fun requireStoreId(): String {
        val settings = prefs.settings.value
        check(settings.storageMode == StorageMode.CLOUD) { "Cloud held bills require CLOUD storage mode." }
        return checkNotNull(settings.cloudStoreId?.takeIf(String::isNotBlank)) {
            "Cloud held bills require a selected store."
        }
    }

    private suspend fun <T> cloudCall(action: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudHeldCartException) {
        throw e
    } catch (e: Exception) {
        throw CloudHeldCartException("$action failed: ${e.message ?: "Supabase request failed."}", e)
    }

    private fun JsonObject.toHeldCart(): HeldCartEntity {
        fun text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        fun number(key: String) = this[key]?.jsonPrimitive?.longOrNull
        return HeldCartEntity(
            id = checkNotNull(text("id")) { "A cloud held bill has no ID." },
            label = checkNotNull(text("label")) { "A cloud held bill has no label." },
            customerId = text("customer_id"),
            interState = number("inter_state") == 1L,
            billDiscountPaise = number("bill_discount_paise") ?: 0L,
            itemsJson = checkNotNull(text("items_json")) { "A cloud held bill has no cart data." },
            itemCount = (number("item_count") ?: 0L).toInt(),
            createdAt = number("created_at") ?: 0L,
            updatedAt = number("updated_at") ?: 0L,
            deletedAt = number("deleted_at"),
            dirty = false,
        )
    }

    private fun JsonObject.isNull(key: String) = this[key]?.jsonPrimitive?.contentOrNull == null
}