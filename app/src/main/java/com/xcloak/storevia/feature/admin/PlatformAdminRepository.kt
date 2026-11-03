package com.xcloak.storevia.feature.admin

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AdminStoreDetails(
    val id: String,
    @SerialName("store_id") val storeId: String,
    val name: String,
    val address: String = "",
    val phone: String = "",
    val gstin: String? = null,
)

@Serializable
data class AdminStore(
    @SerialName("store_id") val storeId: String,
    val plan: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("store_type") val storeType: String = "GENERAL",
    val store: AdminStoreDetails? = null,
)

@Serializable
data class AdminPayment(
    val id: String,
    @SerialName("store_id") val storeId: String,
    @SerialName("amount_minor") val amountMinor: Long? = null,
    val currency: String = "INR",
    val provider: String,
    @SerialName("product_id") val productId: String? = null,
    val status: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AdminInsights(
    val total: Int = 0,
    val active: Int = 0,
    val held: Int = 0,
    val pro: Int = 0,
    val recordedAmountMinor: Long = 0,
)

@Serializable
data class AdminOverview(
    val stores: List<AdminStore> = emptyList(),
    val payments: List<AdminPayment> = emptyList(),
    val insights: AdminInsights = AdminInsights(),
)

@Serializable
private data class AdminActionResult(val ok: Boolean = true, val storeId: String? = null)

@Singleton
class PlatformAdminRepository @Inject constructor(
    private val client: SupabaseClient,
    private val account: CloudAccountRepository,
) {
    suspend fun hasAccess() = account.isPlatformAdmin()

    suspend fun overview(): AdminOverview = call("overview")

    suspend fun createStore(name: String, ownerEmail: String, storeType: String) {
        call<AdminActionResult>("create_store") {
            put("name", name)
            put("ownerEmail", ownerEmail)
            put("storeType", storeType)
        }
    }

    suspend fun updateStore(store: AdminStore, name: String, address: String, phone: String, gstin: String, storeType: String) {
        call<AdminActionResult>("update_store") {
            put("storeId", store.storeId)
            put("name", name)
            put("address", address)
            put("phone", phone)
            put("gstin", gstin)
            put("storeType", storeType)
        }
    }

    suspend fun setStatus(storeId: String, action: String) {
        call<AdminActionResult>(action) { put("storeId", storeId) }
    }

    suspend fun recordPayment(storeId: String, amountMinor: Long, note: String) {
        call<AdminActionResult>("record_payment") {
            put("storeId", storeId)
            put("amountMinor", amountMinor)
            put("note", note)
        }
    }

    private suspend inline fun <reified T : Any> call(
        action: String,
        noinline extra: JsonObjectBuilder.() -> Unit = {},
    ): T {
        val response = client.functions.invoke(
            "platform-admin",
            body = buildJsonObject {
                put("action", action)
                extra()
            },
        )
        return response.body<T>()
    }
}