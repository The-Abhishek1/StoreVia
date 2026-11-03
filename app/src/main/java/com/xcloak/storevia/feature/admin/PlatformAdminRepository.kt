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
data class AdminSubscription(
    @SerialName("store_id") val storeId: String,
    val plan: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class AdminCloudAccount(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("created_at") val createdAt: String,
    val subscriptions: List<AdminSubscription> = emptyList(),
)

@Serializable
data class AdminOverview(val accounts: List<AdminCloudAccount>)

@Serializable
private data class AdminActionResult(val ok: Boolean = true)

@Singleton
class PlatformAdminRepository @Inject constructor(
    private val client: SupabaseClient,
    private val account: CloudAccountRepository,
) {
    suspend fun hasAccess() = account.isPlatformAdmin()

    suspend fun overview(): AdminOverview = call("overview")

    suspend fun setPlan(storeId: String, plan: String) {
        call<AdminActionResult>("set_plan") {
            put("storeId", storeId)
            put("plan", plan)
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
