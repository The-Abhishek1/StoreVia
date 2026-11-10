package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.BuildConfig
import com.xcloak.storevia.data.AppBootstrap
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.dao.StoreDao
import com.xcloak.storevia.core.sync.SyncEngine
import com.xcloak.storevia.core.sync.SyncTables
import com.xcloak.storevia.core.store.StoreType
import com.xcloak.storevia.data.backup.BackupManager
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.Phone
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import io.ktor.client.call.body
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CloudSyncSummary(val pushed: Int, val pulled: Int)
data class CloudPlanSnapshot(
    val plan: String,
    val status: String,
    val expiresAt: String?,
    val rewardedExpiresAt: String? = null,
) {
    fun hasActiveProAccess(now: java.time.Instant = java.time.Instant.now()): Boolean {
        fun parseExpiry(value: String?) = value?.let {
            runCatching { java.time.Instant.parse(it) }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
        }
        val paidAccess = plan.equals("pro", ignoreCase = true) &&
            status.equals("active", ignoreCase = true) &&
            parseExpiry(expiresAt)?.isAfter(now) == true
        val rewardedAccess = parseExpiry(rewardedExpiresAt)?.isAfter(now) == true
        return paidAccess || rewardedAccess
    }
}

@Serializable
private data class AdRewardRequestResponse(val customData: String)

class CloudProRequiredException : IllegalStateException("This cloud feature requires an active Pro subscription.")
data class CloudStoreOption(val id: String, val name: String, val role: String, val storeType: StoreType)

internal sealed interface CloudStoreSelection {
    data class Existing(val storeId: String) : CloudStoreSelection
    data object CreateNew : CloudStoreSelection
    data object NoStore : CloudStoreSelection
}

internal fun selectCloudStore(
    storageMode: StorageMode?,
    selectedStoreId: String?,
    membershipStoreIds: List<String>,
    createIfMissing: Boolean,
): CloudStoreSelection {
    if (storageMode != StorageMode.CLOUD && selectedStoreId == null) {
        return if (createIfMissing) CloudStoreSelection.CreateNew else CloudStoreSelection.NoStore
    }
    if (selectedStoreId != null) {
        check(selectedStoreId in membershipStoreIds) {
            "The selected cloud store is not available to this account. Local data was not changed."
        }
        return CloudStoreSelection.Existing(selectedStoreId)
    }
    return membershipStoreIds.firstOrNull()
        ?.let(CloudStoreSelection::Existing)
        ?: if (createIfMissing) CloudStoreSelection.CreateNew else CloudStoreSelection.NoStore
}

internal fun selectCloudSignupStore(
    selectedStoreId: String?,
    membershipStoreIds: List<String>,
): CloudStoreSelection {
    if (selectedStoreId == null) return CloudStoreSelection.CreateNew
    check(selectedStoreId in membershipStoreIds) {
        "The pending cloud signup store is not available to this account."
    }
    return CloudStoreSelection.Existing(selectedStoreId)
}

internal fun requiresLocalStoreForConnection(
    selection: CloudStoreSelection,
    storageMode: StorageMode?,
): Boolean = selection == CloudStoreSelection.CreateNew || storageMode == StorageMode.LOCAL

internal fun autoSelectedCloudStoreId(selectedStoreId: String?, membershipStoreIds: List<String>): String? =
    selectedStoreId ?: membershipStoreIds.singleOrNull()

internal fun shouldResetLocalCacheForCloudStore(
    transferLocalDataset: Boolean,
    selectedStoreId: String?,
    targetStoreId: String,
): Boolean = !transferLocalDataset && selectedStoreId != targetStoreId

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
    }

    suspend fun signInWithPhone(phone: String, password: String) {
        requireConfigured()
        client.auth.signInWith(Phone) {
            this.phone = phone
            this.password = password
        }
    }

    suspend fun sendPasswordReset(email: String) {
        requireConfigured()
        client.auth.resetPasswordForEmail(email.trim(), redirectUrl = AUTH_CALLBACK_URL)
    }

    suspend fun signOut() {
        client.auth.signOut()
        prefs.update {
            copy(
                cloudStoreId = null,
                cloudRole = null,
                cloudSignupPending = false,
                storageMode = if (storageMode == StorageMode.LOCAL) storageMode else null,
            )
        }
    }

    suspend fun connectAndSync(): CloudSyncSummary =
        checkNotNull(connectAndSync(createIfMissing = true))

    suspend fun completeCloudSignup(): CloudSyncSummary =
        checkNotNull(connectAndSync(createIfMissing = true, forceCreateNew = true))

    suspend fun connectExistingStore(): CloudSyncSummary? {
        val settings = prefs.settings.value
        val memberships = acceptInvitesAndLoadMemberships()
        val selectedStoreId = autoSelectedCloudStoreId(
            selectedStoreId = settings.cloudStoreId,
            membershipStoreIds = memberships.mapNotNull { it["store_id"]?.jsonPrimitive?.contentOrNull },
        ) ?: return null
        return connectAndSync(
            createIfMissing = false,
            explicitStoreId = selectedStoreId,
            knownMemberships = memberships,
            invitationsAccepted = true,
        )
    }

    suspend fun availableCloudStores(): List<CloudStoreOption> {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        val memberships = acceptInvitesAndLoadMemberships()
        return memberships.map { membership ->
            val id = membership["store_id"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("A store membership did not include its store ID.")
            val store = cloudOperation("Loading cloud store details") {
                client.from("store").select {
                    filter { eq("id", id) }
                }.decodeList<JsonObject>().firstOrNull()
            } ?: throw IllegalStateException("The selected cloud store is unavailable.")
            CloudStoreOption(
                id = id,
                name = store["name"]?.jsonPrimitive?.contentOrNull ?: id,
                role = membership["role"]?.jsonPrimitive?.contentOrNull ?: "MEMBER",
                storeType = membership["store_type"]?.jsonPrimitive?.contentOrNull
                    ?.let(StoreType::fromName)
                    ?: StoreType.GENERAL,
            )
        }
    }

    suspend fun connectSelectedStore(storeId: String): CloudSyncSummary? {
        check(prefs.settings.value.storageMode != StorageMode.LOCAL) {
            "A Local store must be transferred to a separate new Cloud store."
        }
        return connectAndSync(createIfMissing = false, explicitStoreId = storeId)
    }

    private suspend fun connectAndSync(
        createIfMissing: Boolean,
        explicitStoreId: String? = null,
        knownMemberships: List<JsonObject>? = null,
        invitationsAccepted: Boolean = false,
        forceCreateNew: Boolean = false,
    ): CloudSyncSummary? {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        val localStore = storeDao.get()
        val settings = prefs.settings.value
        if (!invitationsAccepted) {
            cloudOperation("Accepting store invitations") {
                client.postgrest.rpc("accept_invites")
            }
        }
        val memberships = knownMemberships ?: loadStoreMemberships()
        val membershipIds = memberships.mapNotNull { it["store_id"]?.jsonPrimitive?.contentOrNull }
        val selectedStoreId = explicitStoreId ?: settings.cloudStoreId
        val selection = if (forceCreateNew) {
            selectCloudSignupStore(selectedStoreId, membershipIds)
        } else {
            selectCloudStore(
                storageMode = settings.storageMode,
                selectedStoreId = selectedStoreId,
                membershipStoreIds = membershipIds,
                createIfMissing = createIfMissing,
            )
        }
        if (selection == CloudStoreSelection.NoStore) return null
        check(!requiresLocalStoreForConnection(selection, settings.storageMode) || localStore != null) {
            "Finish setting up your store before enabling cloud sync."
        }

        val transferLocalDataset: Boolean
        val remoteStoreId: String
        val role: String?
        val remoteStoreType: StoreType
        when (selection) {
            CloudStoreSelection.CreateNew -> {
                val storeToTransfer = checkNotNull(localStore) {
                    "Finish setting up your store before enabling cloud sync."
                }
                if (prefs.settings.value.storageMode != StorageMode.LOCAL) {
                    prefs.update {
                        copy(storageMode = StorageMode.LOCAL, cloudStoreId = null, cloudRole = null)
                    }
                }
                remoteStoreId = UUID.randomUUID().toString()
                role = "OWNER"
                remoteStoreType = settings.storeType
                transferLocalDataset = true
                cloudOperation("Creating the cloud store") {
                    client.postgrest.rpc(
                        "create_store_with_details",
                        buildJsonObject {
                            put("p_store_id", remoteStoreId)
                            put("p_name", storeToTransfer.name)
                            put("p_owner_name", storeToTransfer.ownerName.ifBlank { storeToTransfer.name })
                            put("p_store_type", remoteStoreType.name)
                        },
                    )
                }
                // Remember the destination before transferring so a retry after process death resumes this
                // newly-created store instead of creating another one or selecting an existing membership.
                prefs.update {
                    copy(cloudStoreId = remoteStoreId, cloudRole = role)
                }
            }
            is CloudStoreSelection.Existing -> {
                remoteStoreId = selection.storeId
                val membership = memberships.first { it["store_id"]?.jsonPrimitive?.contentOrNull == remoteStoreId }
                role = membership["role"]?.jsonPrimitive?.contentOrNull
                remoteStoreType = membership["store_type"]?.jsonPrimitive?.contentOrNull
                    ?.let(StoreType::fromName)
                    ?: settings.storeType
                transferLocalDataset = settings.storageMode == StorageMode.LOCAL
            }
            CloudStoreSelection.NoStore -> return null
        }

        if (transferLocalDataset) backupManager.autoBackup()
        if (shouldResetLocalCacheForCloudStore(transferLocalDataset, settings.cloudStoreId, remoteStoreId)) {
            backupManager.autoBackup()
            SyncEngine(db, SupabaseRemote(client, remoteStoreId), remoteStoreId).resetLocalData()
        }
        val result = cloudOperation("Synchronizing store data") {
            syncMutex.withLock {
                val engine = SyncEngine(db, SupabaseRemote(client, remoteStoreId), remoteStoreId)
                if (transferLocalDataset) engine.transferLocalDatasetAndVerify() else engine.pullOnly()
            }
        }
        prefs.update {
            copy(
                cloudStoreId = remoteStoreId,
                cloudRole = role ?: cloudRole,
                storeType = remoteStoreType,
                storageMode = StorageMode.CLOUD,
                cloudSignupPending = false,
            )
        }
        bootstrap.ensureSeeded()
        return CloudSyncSummary(result.pushed, result.pulled)
    }

    private suspend fun acceptInvitesAndLoadMemberships(): List<JsonObject> {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        cloudOperation("Accepting store invitations") {
            client.postgrest.rpc("accept_invites")
        }
        return loadStoreMemberships()
    }

    private suspend fun loadStoreMemberships(): List<JsonObject> =
        cloudOperation("Loading store membership") {
            client.postgrest.rpc("my_store").decodeList<JsonObject>()
        }

    suspend fun syncNow(): CloudSyncSummary {
        requireConfigured()
        check(prefs.settings.value.storageMode == StorageMode.CLOUD) { "This store is not in Cloud mode." }
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        checkNotNull(storeDao.get()) { "No local store is set up." }
        val cloudStoreId = checkNotNull(prefs.settings.value.cloudStoreId) { "Connect this store before syncing." }
        val result = cloudOperation("Synchronizing store data") {
            syncMutex.withLock {
                SyncEngine(db, SupabaseRemote(client, cloudStoreId), cloudStoreId).pullOnly()
            }
        }
        return CloudSyncSummary(result.pushed, result.pulled)
    }

    suspend fun joinInvitedStore(): CloudSyncSummary {
        requireConfigured()
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        check(prefs.settings.value.storageMode != StorageMode.LOCAL) {
            "A Local store cannot join an existing cloud store. Connect it to create a separate Cloud store and preserve its data."
        }
        cloudOperation("Accepting store invitations") {
            client.postgrest.rpc("accept_invites")
        }
        val memberships = cloudOperation("Loading store membership") {
            client.postgrest.rpc("my_store").decodeList<JsonObject>()
        }
        check(memberships.isNotEmpty()) { "No store invitation found for this account." }
        return connectAndSync()
    }

    private suspend fun <T> cloudOperation(label: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("$label failed.", e)
        }

    suspend fun uploadBackup(passphrase: String?) {
        val storeId = requireConnectedStore()
        requireActiveProAccess()
        val output = ByteArrayOutputStream()
        backupManager.writeBackup(output, passphrase)
        client.storage.from(BACKUP_BUCKET).upload(backupPath(storeId), output.toByteArray()) { upsert = true }
    }

    suspend fun downloadBackup(): ByteArray {
        val storeId = requireConnectedStore()
        requireActiveProAccess()
        return client.storage.from(BACKUP_BUCKET).downloadAuthenticated(backupPath(storeId))
    }

    suspend fun planSnapshot(): CloudPlanSnapshot? {
        requireConfigured()
        if (prefs.settings.value.storageMode != StorageMode.CLOUD) return null
        val userId = client.auth.currentSessionOrNull()?.user?.id ?: return null
        val storeId = prefs.settings.value.cloudStoreId ?: return null
        val rows = client.from("subscriptions").select {
            filter { eq("store_id", storeId) }
        }.decodeList<JsonObject>()
        val row = rows.firstOrNull() ?: return null
        val rewards = client.from("ad_reward_entitlements").select {
            filter { eq("store_id", storeId); eq("user_id", userId) }
        }.decodeList<JsonObject>()
        return CloudPlanSnapshot(
            plan = row["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
            status = row["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            expiresAt = row["expires_at"]?.jsonPrimitive?.contentOrNull,
            rewardedExpiresAt = rewards.firstOrNull()?.get("expires_at")?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun requestRewardedProCustomData(): String {
        val storeId = requireConnectedStore()
        val response = client.functions.invoke(
            "admob-reward",
            body = buildJsonObject { put("storeId", storeId) },
        )
        return response.body<AdRewardRequestResponse>().customData
    }

    suspend fun verifyPlayPurchase(storeId: String, purchaseToken: String) {
        requireConfigured()
        check(requireConnectedStore() == storeId) { "Connect this store before upgrading." }
        client.functions.invoke(
            "verify-play-purchase",
            body = buildJsonObject {
                put("storeId", storeId)
                put("purchaseToken", purchaseToken)
            },
        )
    }

    suspend fun requireActiveProAccess() {
        if (planSnapshot()?.hasActiveProAccess() != true) throw CloudProRequiredException()
    }

    suspend fun checkPlatformAdminAccess(): Boolean {
        if (!isConfigured || client.auth.currentSessionOrNull() == null) return false
        return try {
            client.functions.invoke("platform-admin", body = buildJsonObject { put("action", "access_check") })
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isPlatformAdminAccessDenied(error)) {
                false
            } else {
                throw IllegalStateException("Could not verify platform administrator access.", error)
            }
        }
    }

    suspend fun isPlatformAdmin(): Boolean =
        try {
            checkPlatformAdminAccess()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }

    private suspend fun requireConnectedStore(): String {
        requireConfigured()
        check(prefs.settings.value.storageMode == StorageMode.CLOUD) { "Offline stores cannot use cloud services." }
        check(client.auth.currentSessionOrNull() != null) { "Sign in to your cloud account first." }
        checkNotNull(storeDao.get()) { "No local store is set up." }
        val cloudStoreId = checkNotNull(prefs.settings.value.cloudStoreId) { "Connect this store before using cloud backup." }
        return cloudStoreId
    }

    fun isOfflineStoreMode(): Boolean = prefs.settings.value.storageMode == StorageMode.LOCAL
    fun hasConnectedCloudStore(): Boolean =
        prefs.settings.value.storageMode == StorageMode.CLOUD && prefs.settings.value.cloudStoreId != null

    private fun backupPath(storeId: String) = "$storeId/latest.svbackup"

    private fun requireConfigured() {
        check(isConfigured) { "Cloud services are not configured. Add your Supabase URL and public anon key to local.properties." }
    }

    private companion object {
        const val BACKUP_BUCKET = "storevia-backups"
        const val AUTH_CALLBACK_URL = "storevia://auth/callback"
    }
}

internal fun isPlatformAdminAccessDenied(error: Throwable): Boolean {
    val message = generateSequence(error) { it.cause }
        .take(10)
        .joinToString(" ") { it.message.orEmpty() }
    return message.contains("platform administrator access required", ignoreCase = true) ||
        Regex("""\b(?:HTTP(?:\s+status)?\s+)?403\b""", RegexOption.IGNORE_CASE).containsMatchIn(message)
}

internal fun cloudAccountIdentifier(email: String?, phone: String?): String? =
    email?.takeIf(String::isNotBlank) ?: phone?.takeIf(String::isNotBlank)

internal fun supabaseAuthorizationHeader(anonKey: String): String = "Bearer $anonKey"
