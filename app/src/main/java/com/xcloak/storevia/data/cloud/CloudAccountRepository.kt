package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.BuildConfig
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.sync.SyncEngine
import com.xcloak.storevia.data.backup.BackupManager
import com.xcloak.storevia.data.prefs.AppPrefs
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class CloudSyncSummary(val pushed: Int, val pulled: Int)
data class CloudPlanSnapshot(val plan: String, val status: String, val expiresAt: String?)

@Singleton
class CloudAccountRepository @Inject constructor(
    private val client: SupabaseClient,
    private val storeDao: StoreDao,
    private val db: StorviaDatabase,
    private val prefs: AppPrefs,
    private val backupManager: BackupManager,
    private val bootstrap: AppBootstrap,
) {
    private val syncMutex = Mutex()

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_ANON_KEY.isNotBlank() &&
            !BuildConfig.SUPABASE_URL.contains("YOUR-PROJECT")

    fun signedInEmail(): String? = client.auth.currentSessionOrNull()?.user?.email

    suspend fun signUp(email: String, password: String) {
        requireConfigured()
        client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String) {
        requireConfigured()
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
        prefs.update { copy(cloudStoreId = null, cloudRole = null) }
    }

    suspend fun connectAndSync(): CloudSyncSummary {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        val localStore = checkNotNull(storeDao.get()) { "Finish setting up your store before enabling cloud sync." }
        client.postgrest.rpc("accept_invites")
        val memberships = client.postgrest.rpc("my_store").decodeList<JsonObject>()
        val membership = memberships.firstOrNull()
        val remoteStoreId = membership?.get("store_id")?.jsonPrimitive?.contentOrNull
        val role = membership?.get("role")?.jsonPrimitive?.contentOrNull

        if (remoteStoreId == null) {
            client.postgrest.rpc(
                "create_store",
                buildJsonObject {
                    put("p_store_id", localStore.id)
                    put("p_name", localStore.name)
                },
            )
            prefs.update { copy(cloudStoreId = localStore.id, cloudRole = "OWNER") }
        } else if (remoteStoreId != localStore.id) {
            val previouslyLinkedStore = prefs.settings.value.cloudStoreId
            check(previouslyLinkedStore == null || previouslyLinkedStore == remoteStoreId) {
                "This account is linked to a different store than before. Local data was not changed."
            }
            check(isFreshLocalStore(localStore.name)) {
                "This account belongs to a different store. Back up this phone and restore that store's data before connecting. Local data was not changed."
            }
            backupManager.autoBackup()
            val engine = SyncEngine(db, SupabaseRemote(client, remoteStoreId), remoteStoreId)
            engine.resetLocalData()
            prefs.update { copy(cloudStoreId = remoteStoreId, cloudRole = role) }
            val result = syncMutex.withLock { engine.sync() }
            bootstrap.ensureSeeded()
            return CloudSyncSummary(result.pushed, result.pulled)
        } else {
            prefs.update { copy(cloudStoreId = remoteStoreId, cloudRole = role) }
        }

        val result = syncMutex.withLock { SyncEngine(db, SupabaseRemote(client, localStore.id), localStore.id).sync() }
        bootstrap.ensureSeeded()
        return CloudSyncSummary(result.pushed, result.pulled)
    }

    suspend fun syncNow(): CloudSyncSummary {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        val localStore = checkNotNull(storeDao.get()) { "No local store is set up." }
        val cloudStoreId = prefs.settings.value.cloudStoreId
        check(cloudStoreId == localStore.id) { "Connect this store before syncing." }
        val result = syncMutex.withLock { SyncEngine(db, SupabaseRemote(client, cloudStoreId), cloudStoreId).sync() }
        return CloudSyncSummary(result.pushed, result.pulled)
    }

    suspend fun uploadBackup(passphrase: String?) {
        val storeId = requireConnectedStore()
        val output = ByteArrayOutputStream()
        backupManager.writeBackup(output, passphrase)
        client.storage.from(BACKUP_BUCKET).upload(backupPath(storeId), output.toByteArray()) { upsert = true }
    }

    suspend fun downloadBackup(): ByteArray {
        val storeId = requireConnectedStore()
        return client.storage.from(BACKUP_BUCKET).downloadAuthenticated(backupPath(storeId))
    }

    suspend fun planSnapshot(): CloudPlanSnapshot? {
        requireConfigured()
        if (client.auth.currentSessionOrNull() == null) return null
        val storeId = prefs.settings.value.cloudStoreId ?: return null
        val rows = client.from("subscriptions").select {
            filter { eq("store_id", storeId) }
        }.decodeList<JsonObject>()
        val row = rows.firstOrNull() ?: return null
        return CloudPlanSnapshot(
            plan = row["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
            status = row["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            expiresAt = row["expires_at"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun verifyPlayPurchase(storeId: String, purchaseToken: String) {
        requireConfigured()
        check(prefs.settings.value.cloudStoreId == storeId) { "Connect this store before upgrading." }
        client.functions.invoke(
            "verify-play-purchase",
            body = buildJsonObject {
                put("storeId", storeId)
                put("purchaseToken", purchaseToken)
            },
        )
    }

    suspend fun isPlatformAdmin(): Boolean {
        if (!isConfigured || client.auth.currentSessionOrNull() == null) return false
        return runCatching {
            client.functions.invoke("platform-admin", body = buildJsonObject { put("action", "access_check") })
        }.isSuccess
    }

    private suspend fun requireConnectedStore(): String {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        val localStoreId = checkNotNull(storeDao.get()) { "No local store is set up." }.id
        val cloudStoreId = prefs.settings.value.cloudStoreId
        check(cloudStoreId == localStoreId) { "Connect this store before using cloud backup." }
        return cloudStoreId
    }

    private fun isFreshLocalStore(name: String): Boolean {
        if (name != "My Store" || prefs.settings.value.onboardingDone) return false
        val tablesThatIndicateUserData = listOf(
            "categories", "products", "product_barcodes", "batches", "stock_movements",
            "customers", "sales", "sale_lines", "payments", "customer_ledger",
            "suppliers", "purchases", "purchase_lines", "supplier_ledger", "staff", "audit_log",
        )
        return tablesThatIndicateUserData.none { table ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst() && cursor.getLong(0) > 0
            }
        }
    }

    private fun backupPath(storeId: String) = "$storeId/latest.svbackup"

    private fun requireConfigured() {
        check(isConfigured) { "Cloud services are not configured. Add your Supabase URL and public anon key to local.properties." }
    }

    private companion object { const val BACKUP_BUCKET = "storevia-backups" }
}