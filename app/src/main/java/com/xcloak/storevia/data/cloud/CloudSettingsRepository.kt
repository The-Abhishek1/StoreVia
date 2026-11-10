package com.xcloak.storevia.data.cloud

import android.content.Context
import com.xcloak.storevia.core.access.AuditAction
import com.xcloak.storevia.core.database.entity.AuditEntity
import com.xcloak.storevia.core.database.entity.BranchEntity
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.core.database.entity.StaffEntity
import com.xcloak.storevia.core.access.Role
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class CloudSettingsException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal data class CloudMember(
    val id: String,
    val name: String,
    val role: Role,
)

data class CloudStaffInvite(
    val email: String,
    val role: Role,
)

internal fun cloudMemberRole(value: String?): Role? = when (value?.uppercase()) {
    "OWNER" -> Role.OWNER
    "MANAGER" -> Role.MANAGER
    "CASHIER" -> Role.CASHIER
    else -> null
}

internal fun cloudMember(row: JsonObject): CloudMember? {
    val id = row.string("member_user_id") ?: return null
    val role = cloudMemberRole(row.string("role")) ?: return null
    return CloudMember(id, row.string("display_name")?.takeIf(String::isNotBlank) ?: "Cloud user", role)
}

@Singleton
class CloudSettingsRepository @Inject constructor(
    private val client: SupabaseClient,
    private val prefs: AppPrefs,
    @ApplicationContext context: Context,
) {
    private val localSelections = context.getSharedPreferences("cloud_branch_selection", Context.MODE_PRIVATE)
    private val selectionRevision = MutableStateFlow(0L)

    fun isCloudMode(): Boolean = prefs.settings.value.storageMode == StorageMode.CLOUD

    suspend fun loadStore(): StoreEntity = cloudCall("Read cloud store settings") {
        val storeId = requireStoreId()
        val row = client.from("store").select {
            filter { eq("store_id", storeId) }
            limit(1)
        }.decodeList<JsonObject>().firstOrNull()
            ?: throw CloudSettingsException("The selected cloud store is unavailable.")
        row.toStoreEntity(storeId)
    }

    suspend fun saveStore(
        name: String,
        ownerName: String,
        address: String,
        city: String,
        phone: String,
        gstin: String?,
        stateCode: String,
        invoicePrefix: String,
        allowNegativeStock: Boolean,
    ): StoreEntity = cloudCall("Save cloud store settings") {
        val storeId = requireStoreId()
        client.postgrest.rpc(
            "storevia_update_store_settings",
            buildJsonObject {
                put("p_store_id", storeId)
                put("p_name", name)
                put("p_owner_name", ownerName)
                put("p_address", address)
                put("p_city", city)
                put("p_phone", phone)
                put("p_gstin", gstin)
                put("p_state_code", stateCode)
                put("p_invoice_prefix", invoicePrefix)
                put("p_allow_negative_stock", if (allowNegativeStock) 1 else 0)
                put("p_updated_at", System.currentTimeMillis())
            },
        )
        val saved = loadStore()
        check(saved.name == name) { "Cloud store settings were not updated." }
        saved
    }

    suspend fun loadBranches(): List<BranchEntity> = cloudCall("Read cloud branches") {
        val storeId = requireStoreId()
        client.from("branches").select {
            filter { eq("store_id", storeId) }
            order("name", Order.ASCENDING)
        }.decodeList<JsonObject>()
            .map(JsonObject::toBranchEntity)
            .filter { it.deletedAt == null }
    }

    suspend fun addBranch(name: String): BranchEntity = cloudCall("Add cloud branch") {
        val storeId = requireStoreId()
        val id = UUID.randomUUID().toString()
        client.postgrest.rpc(
            "storevia_add_branch",
            buildJsonObject {
                put("p_store_id", storeId)
                put("p_branch_id", id)
                put("p_name", name.trim())
                put("p_now", System.currentTimeMillis())
            },
        )
        loadBranches().firstOrNull { it.id == id }
            ?: throw CloudSettingsException("The cloud branch was added but could not be reloaded.")
    }

    suspend fun renameBranch(id: String, name: String): BranchEntity = cloudCall("Rename cloud branch") {
        val storeId = requireStoreId()
        client.postgrest.rpc(
            "storevia_rename_branch",
            buildJsonObject {
                put("p_store_id", storeId)
                put("p_branch_id", id)
                put("p_name", name.trim())
                put("p_now", System.currentTimeMillis())
            },
        )
        loadBranches().firstOrNull { it.id == id }
            ?: throw CloudSettingsException("The cloud branch was renamed but could not be reloaded.")
    }

    suspend fun loadMembers(): List<StaffEntity> = cloudCall("Read cloud staff memberships") {
        val rows = client.postgrest.rpc(
            "storevia_list_store_members",
            buildJsonObject { put("p_store_id", requireStoreId()) },
        ).decodeList<JsonObject>()
        rows.mapNotNull(::cloudMember).map { member ->
            StaffEntity(id = member.id, name = member.name, role = member.role, isActive = true, dirty = false)
        }
    }

    suspend fun loadStaffInvites(): List<CloudStaffInvite> = cloudCall("Read cloud staff invitations") {
        val storeId = requireStoreId()
        client.from("store_invites").select {
            filter { eq("store_id", storeId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<JsonObject>().mapNotNull { row ->
            val email = row["email"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val role = cloudMemberRole(row["role"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
            CloudStaffInvite(email, role)
        }
    }

    suspend fun inviteStaffMember(email: String, role: Role) = cloudCall("Invite cloud staff member") {
        require(role == Role.MANAGER || role == Role.CASHIER) { "Cloud staff invitations must be Manager or Cashier." }
        client.postgrest.rpc(
            "invite_member",
            buildJsonObject {
                put("p_store_id", requireStoreId())
                put("p_email", email.trim().lowercase())
                put("p_role", role.name)
            },
        )
    }

    suspend fun loadAuditLog(): List<AuditEntity> = cloudCall("Read cloud activity log") {
        val storeId = requireStoreId()
        client.from("audit_log").select {
            filter { eq("store_id", storeId) }
            order("created_at", Order.DESCENDING)
            limit(500)
        }.decodeList<JsonObject>().map { row ->
            AuditEntity(
                id = row.string("id") ?: UUID.randomUUID().toString(),
                createdAt = row.long("created_at") ?: 0L,
                actorId = row.string("actor_id"),
                actorName = row.string("actor_name") ?: "Cloud user",
                actorRole = row.string("actor_role") ?: "",
                action = row.string("action")?.let { runCatching { AuditAction.valueOf(it) }.getOrNull() }
                    ?: AuditAction.SETTINGS_CHANGED,
                entityType = row.string("entity_type"),
                entityId = row.string("entity_id"),
                summary = row.string("summary") ?: "",
                dirty = false,
            )
        }
    }

    fun selectedBranchId(): String? {
        val storeId = prefs.settings.value.cloudStoreId ?: return null
        return localSelections.getString(storeId, null)
    }

    fun observeSelectedBranchId(): Flow<String?> = combine(
        prefs.settings.map { it.cloudStoreId }.distinctUntilChanged(),
        selectionRevision,
    ) { storeId, _ ->
        storeId?.let { localSelections.getString(it, null) }
    }.distinctUntilChanged()

    fun setSelectedBranchId(branchId: String?) {
        val storeId = prefs.settings.value.cloudStoreId ?: return
        localSelections.edit().putString(storeId, branchId).apply()
        selectionRevision.update { it + 1 }
    }

    private fun requireStoreId(): String {
        check(prefs.settings.value.storageMode == StorageMode.CLOUD) { "Cloud settings are available only in Cloud mode." }
        return checkNotNull(prefs.settings.value.cloudStoreId) { "No cloud store is selected." }
    }

    private suspend fun <T> cloudCall(action: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudSettingsException) {
        throw e
    } catch (e: Exception) {
        throw CloudSettingsException("$action failed: ${e.message ?: "Supabase request failed."}", e)
    }
}

private fun JsonObject.toStoreEntity(storeId: String) = StoreEntity(
    id = storeId,
    name = string("name") ?: "My Store",
    ownerName = string("owner_name").orEmpty(),
    address = string("address").orEmpty(),
    city = string("city").orEmpty(),
    phone = string("phone").orEmpty(),
    gstin = string("gstin"),
    stateCode = string("state_code") ?: "29",
    invoicePrefix = string("invoice_prefix") ?: "INV",
    nextInvoiceNo = long("next_invoice_no") ?: 1L,
    allowNegativeStock = long("allow_negative_stock") != 0L,
    createdAt = long("created_at") ?: 0L,
    updatedAt = long("updated_at") ?: 0L,
    dirty = false,
)

private fun JsonObject.toBranchEntity() = BranchEntity(
    id = string("id") ?: UUID.randomUUID().toString(),
    name = string("name") ?: "Branch",
    address = string("address").orEmpty(),
    city = string("city").orEmpty(),
    phone = string("phone").orEmpty(),
    isActive = long("is_active") != 0L,
    selectedOnDevice = false,
    createdAt = long("created_at") ?: 0L,
    updatedAt = long("updated_at") ?: 0L,
    deletedAt = long("deleted_at"),
    dirty = false,
)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long? =
    string(name)?.toLongOrNull() ?: string(name)?.let { if (it == "true") 1L else if (it == "false") 0L else null }
