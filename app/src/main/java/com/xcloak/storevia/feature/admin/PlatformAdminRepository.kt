package com.xcloak.storevia.feature.admin

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AdminSubscription(
    @SerialName("store_id") val storeId: String,
    @SerialName("store_name") val storeName: String = storeId,
    val plan: String,
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

internal fun AdminSubscription.isActiveAt(now: Instant = Instant.now()): Boolean {
    if (!status.equals("active", ignoreCase = true)) return false
    val expiry = expiresAt?.let {
        runCatching { Instant.parse(it) }.getOrElse {
            runCatching { OffsetDateTime.parse(expiresAt).toInstant() }.getOrNull()
        }
    } ?: return false
    return expiry.isAfter(now)
}

internal fun AdminSubscription.hasActiveProAccess(now: Instant = Instant.now()): Boolean =
    plan.equals("pro", ignoreCase = true) && isActiveAt(now)

@Serializable
data class AdminCloudAccount(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("created_at") val createdAt: String,
    val subscriptions: List<AdminSubscription> = emptyList(),
    @SerialName("store_membership_count") val storeMembershipCount: Int = subscriptions.size,
)

@Serializable
data class AdminOverview(val accounts: List<AdminCloudAccount>)

@Serializable
data class AdminDeletionUser(
    @SerialName("user_id") val userId: String,
    val email: String,
)

@Serializable
data class AdminDeletionStore(
    @SerialName("store_id") val storeId: String,
    val name: String,
)

@Serializable
data class AdminDeletionPreview(
    @SerialName("target_kind") val targetKind: String,
    @SerialName("target_id") val targetId: String,
    @SerialName("target_name") val targetName: String,
    @SerialName("confirmation_text") val confirmationText: String,
    val users: List<AdminDeletionUser>,
    @SerialName("user_ids") val userIds: List<String>,
    val stores: List<AdminDeletionStore>,
    @SerialName("store_ids") val storeIds: List<String>,
    @SerialName("row_counts") val rowCounts: Map<String, Long>,
    @SerialName("total_rows") val totalRows: Long,
    @SerialName("cloud_backup_count") val cloudBackupCount: Int,
    @SerialName("cloud_backup_store_ids") val cloudBackupStoreIds: List<String>,
)

@Serializable
data class AdminDeletionResult(
    @SerialName("deleted_user_count") val deletedUserCount: Int,
    @SerialName("deleted_store_count") val deletedStoreCount: Int,
    @SerialName("deleted_row_count") val deletedRowCount: Long,
    @SerialName("failed_users") val failedUsers: List<AdminDeletionUserFailure> = emptyList(),
)

@Serializable
data class AdminDeletionUserFailure(
    @SerialName("user_id") val userId: String,
    val email: String,
    val error: String,
)

class PlatformAdminServiceUpdateRequiredException : Exception()

internal fun normalizePlatformAdminError(error: Throwable): Throwable {
    val errorText = generateSequence(error) { it.cause }
        .take(10)
        .joinToString(" ") { it.message.orEmpty() }
    if (errorText.contains("Unknown action", ignoreCase = true) ||
        errorText.contains("platform_admin_deletion_preview", ignoreCase = true)
    ) {
        return PlatformAdminServiceUpdateRequiredException()
    }

    val statusCode = Regex(
        """(?:HTTP(?:\s+status)?\s+|<title>\s*|<h1>\s*)(\d{3})(?:\s+Bad Gateway|\s+Service Unavailable)?""",
        RegexOption.IGNORE_CASE,
    )
        .find(errorText)
        ?.groupValues
        ?.getOrNull(1)
    return if (statusCode != null) {
        IllegalStateException(
            "Platform admin request failed (HTTP $statusCode). No change was made. Check the Supabase Edge Function and retry.",
        )
    } else {
        IllegalStateException(
            "Platform admin request failed. No change was made. Check the connection and retry.",
        )
    }
}

@Serializable
private data class AdminPlanUpdate(
    @SerialName("storeId") val storeId: String,
    val plan: String,
    val status: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Singleton
class PlatformAdminRepository @Inject constructor(
    private val client: SupabaseClient,
    private val account: CloudAccountRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hasAccess() = account.isPlatformAdmin()

    suspend fun overview(): AdminOverview {
        val response = call<JsonObject>("overview")
        if (response["accounts"] !is JsonArray) throw PlatformAdminServiceUpdateRequiredException()
        return json.decodeFromJsonElement(response)
    }

    suspend fun setPlan(storeId: String, plan: String) {
        val result = call<AdminPlanUpdate>("set_plan") {
            put("storeId", storeId)
            put("plan", plan)
        }
        if (result.storeId != storeId || !result.plan.equals(plan, ignoreCase = true) ||
            result.status?.equals("active", ignoreCase = true) != true || result.expiresAt == null
        ) {
            throw PlatformAdminServiceUpdateRequiredException()
        }
        val snapshot = AdminSubscription(
            storeId = result.storeId,
            plan = result.plan,
            status = result.status,
            expiresAt = result.expiresAt,
        )
        if (!snapshot.isActiveAt()) {
            throw IllegalStateException("The server did not activate this plan. Refresh and retry.")
        }
    }

    suspend fun deletionPreview(kind: String, targetId: String): AdminDeletionPreview =
        call("deletion_preview") {
            put("targetKind", kind)
            put("targetId", targetId)
        }

    suspend fun deleteScope(preview: AdminDeletionPreview, confirmationText: String): AdminDeletionResult =
        call("delete_scope") {
            put("targetKind", preview.targetKind)
            put("targetId", preview.targetId)
            put("confirmationText", confirmationText)
            put("expectedUserIds", JsonArray(preview.userIds.map(::JsonPrimitive)))
            put("expectedStoreIds", JsonArray(preview.storeIds.map(::JsonPrimitive)))
            put("expectedRowCounts", JsonObject(preview.rowCounts.mapValues { JsonPrimitive(it.value) }))
            put("expectedTotalRows", preview.totalRows)
            put("expectedCloudBackupCount", preview.cloudBackupCount)
        }

    private suspend inline fun <reified T : Any> call(
        action: String,
        noinline extra: JsonObjectBuilder.() -> Unit = {},
    ): T {
        try {
            val response = client.functions.invoke(
                "platform-admin",
                body = buildJsonObject {
                    put("action", action)
                    extra()
                },
            )
            return response.body<T>()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw normalizePlatformAdminError(error)
        }
    }
}
