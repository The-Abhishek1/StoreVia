package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.BuildConfig
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.sync.SyncEngine
import com.xcloak.storevia.core.store.StoreType
import com.xcloak.storevia.data.backup.BackupManager
import com.xcloak.storevia.data.prefs.AppPrefs
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.Phone
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

    fun signedInIdentifier(): String? {
        val user = client.auth.currentSessionOrNull()?.user ?: return null
        return cloudAccountIdentifier(user.email, user.phone)
    }

    fun hasSignedInSession(): Boolean = client.auth.currentSessionOrNull() != null
    val authSessionStatus get() = client.auth.sessionStatus

    suspend fun signUp(email: String, password: String) {
        requireConfigured()
        client.auth.signUpWith(Email, redirectUrl = AUTH_CALLBACK_URL) {
            this.email = email.trim()
            this.password = password
        }
        if (hasSignedInSession()) prefs.update { copy(localOnlyMode = false) }
    }

    suspend fun resendSignupConfirmation(email: String) {
        requireConfigured()
        require(email.isNotBlank()) { "Email is required." }
        withContext(Dispatchers.IO) {
            val redirect = URLEncoder.encode(AUTH_CALLBACK_URL, StandardCharsets.UTF_8.name())
            val connection = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/resend?redirect_to=$redirect")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty("Authorization", supabaseAuthorizationHeader(BuildConfig.SUPABASE_ANON_KEY))
                connection.setRequestProperty("Content-Type", "application/json")
                val body = buildJsonObject {
                    put("type", "signup")
                    put("email", email.trim())
                }

                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val responseBody = connection.errorStream
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    throw IOException("Confirmation resend failed (HTTP $responseCode): $responseBody")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun signIn(email: String, password: String) {
        requireConfigured()
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        prefs.update { copy(localOnlyMode = false) }
    }

    suspend fun signInWithPhone(phone: String, password: String) {
        requireConfigured()
        client.auth.signInWith(Phone) {
            this.phone = phone
            this.password = password
        }
        prefs.update { copy(localOnlyMode = false) }
    }

    suspend fun sendPasswordReset(email: String) {
        requireConfigured()
        client.auth.resetPasswordForEmail(email.trim(), redirectUrl = AUTH_CALLBACK_URL)
    }

    suspend fun signOut() {
        client.auth.signOut()
        prefs.update { copy(cloudStoreId = null, cloudRole = null, localOnlyMode = false) }
    }

    suspend fun connectAndSync(): CloudSyncSummary =
        checkNotNull(connectAndSync(createIfMissing = true))

    suspend fun connectExistingStore(): CloudSyncSummary? =
        connectAndSync(createIfMissing = false)

    private suspend fun connectAndSync(createIfMissing: Boolean): CloudSyncSummary? {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        val localStore = checkNotNull(storeDao.get()) { "Finish setting up your store before enabling cloud sync." }
        client.postgrest.rpc("accept_invites")
        val memberships = client.postgrest.rpc("my_store").decodeList<JsonObject>()
        val membership = memberships.firstOrNull()
        val remoteStoreId = membership?.get("store_id")?.jsonPrimitive?.contentOrNull
        val role = membership?.get("role")?.jsonPrimitive?.contentOrNull
        val remoteStoreType = membership?.get("store_type")?.jsonPrimitive?.contentOrNull
            ?.let(StoreType::fromName)
            ?: prefs.settings.value.storeType

        if (remoteStoreId == null) {
            if (!createIfMissing) return null
            client.postgrest.rpc(
                "create_store_with_details",
                buildJsonObject {
                    put("p_store_id", localStore.id)
                    put("p_name", localStore.name)
                    put("p_owner_name", localStore.ownerName)
                    put("p_store_type", prefs.settings.value.storeType.name)
                },
            )
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
            val result = syncMutex.withLock { engine.sync() }
            prefs.update {
                copy(
                    cloudStoreId = remoteStoreId,
                    cloudRole = role,
                    storeType = remoteStoreType,
                    localOnlyMode = false,
                )
            }
            bootstrap.ensureSeeded()
            return CloudSyncSummary(result.pushed, result.pulled)
        }

        val targetStoreId = remoteStoreId ?: localStore.id
        val result = syncMutex.withLock { SyncEngine(db, SupabaseRemote(client, targetStoreId), targetStoreId).sync() }
        prefs.update {
            copy(
                cloudStoreId = targetStoreId,
                cloudRole = role ?: if (remoteStoreId == null) "OWNER" else cloudRole,
                storeType = remoteStoreType,
                localOnlyMode = false,
            )
        }
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

    suspend fun joinInvitedStore(): CloudSyncSummary {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        client.postgrest.rpc("accept_invites")
        val memberships = client.postgrest.rpc("my_store").decodeList<JsonObject>()
        check(memberships.isNotEmpty()) { "No store invitation found for this account." }
        return connectAndSync()
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

    private companion object {
        const val BACKUP_BUCKET = "storevia-backups"
        const val AUTH_CALLBACK_URL = "storevia://auth/callback"
    }
}

internal fun cloudAccountIdentifier(email: String?, phone: String?): String? =
    email?.takeIf(String::isNotBlank) ?: phone?.takeIf(String::isNotBlank)

internal fun supabaseAuthorizationHeader(anonKey: String): String = "Bearer $anonKey"
