package com.xcloak.storevia.feature.cloud

import android.app.Activity
import android.content.Context
import android.util.Patterns
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import com.xcloak.storevia.data.cloud.CloudPlanSnapshot
import com.xcloak.storevia.data.cloud.CloudSyncWorker
import com.xcloak.storevia.data.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

data class CloudAccountUiState(
    val accountIdentifier: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val plan: CloudPlanSnapshot? = null,
    val billingReady: Boolean = false,
    val billingBusy: Boolean = false,
    val proPrice: String? = null,
    val proBillingPeriod: String? = null,
    val platformAdmin: Boolean = false,
    val awaitingEmailConfirmation: Boolean = false,
)

@HiltViewModel
class CloudAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloud: CloudAccountRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _ui = MutableStateFlow(CloudAccountUiState(accountIdentifier = cloud.signedInIdentifier()))
    val ui: StateFlow<CloudAccountUiState> = _ui.asStateFlow()
    val settings = prefs.settings
    val isConfigured get() = cloud.isConfigured
    private var proProduct: ProductDetails? = null
    private val billingClient = BillingClient.newBuilder(context)
        .setListener(PurchasesUpdatedListener(::onPurchasesUpdated))
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .build()

    init {
        viewModelScope.launch {
            cloud.authSessionStatus.collect {
                val identifier = cloud.signedInIdentifier()
                _ui.update {
                    it.copy(
                        accountIdentifier = identifier,
                        awaitingEmailConfirmation = if (identifier == null) it.awaitingEmailConfirmation else false,
                    )
                }
                if (identifier != null) refreshPlan()
            }
        }
        refreshPlan()
        connectBilling()
    }

    fun buyPro(activity: Activity) {
        val details = proProduct
        val storeId = prefs.settings.value.cloudStoreId
        if (details == null || storeId == null) {
            _ui.update { it.copy(message = "Connect a store and wait for Play Billing to load the Pro offer.") }
            return
        }
        val offer = details.subscriptionOfferDetails?.firstOrNull()
        if (offer == null) {
            _ui.update { it.copy(message = "No subscription offer is currently available in Google Play.") }
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(accountHash(storeId))
            .build()
        _ui.update { it.copy(billingBusy = true, message = null) }
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _ui.update { it.copy(billingBusy = false, message = result.debugMessage.ifBlank { "Could not start Google Play checkout." }) }
        }
    }

    private fun connectBilling() {
        if (!cloud.isConfigured) return
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    _ui.update { it.copy(message = "Google Play Billing unavailable: ${result.debugMessage}") }
                    return
                }
                val product = QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(com.xcloak.storevia.BuildConfig.PRO_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
                billingClient.queryProductDetailsAsync(
                    QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
                ) { queryResult, response ->
                    val details = response.firstOrNull()
                    val recurringPhase = details?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()
                    val price = recurringPhase?.formattedPrice
                    val billingPeriod = recurringPhase?.billingPeriod?.let(::billingPeriodLabel)
                    proProduct = details
                    _ui.update {
                        it.copy(
                            billingReady = queryResult.responseCode == BillingClient.BillingResponseCode.OK && details != null,
                            proPrice = price,
                            proBillingPeriod = billingPeriod,
                        )
                    }
                    refreshExistingPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                _ui.update { it.copy(billingReady = false) }
            }
        })
    }

    private fun refreshExistingPurchases() {
        val storeId = prefs.settings.value.cloudStoreId ?: return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .filter { com.xcloak.storevia.BuildConfig.PRO_PRODUCT_ID in it.products }
                    .forEach { verifyPurchase(storeId, it) }
            }
        }
    }

    private fun onPurchasesUpdated(result: com.android.billingclient.api.BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            _ui.update { it.copy(billingBusy = false, message = "Purchase cancelled.") }
            return
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases.isNullOrEmpty()) {
            _ui.update { it.copy(billingBusy = false, message = result.debugMessage.ifBlank { "Google Play purchase failed." }) }
            return
        }
        if (purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            _ui.update { it.copy(billingBusy = false, message = "Payment is pending. Pro access starts after Google Play confirms payment.") }
        }
        val storeId = prefs.settings.value.cloudStoreId
        if (storeId == null) {
            _ui.update { it.copy(billingBusy = false, message = "Connect a store before purchasing Pro.") }
            return
        }
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .filter { com.xcloak.storevia.BuildConfig.PRO_PRODUCT_ID in it.products }
            .forEach { verifyPurchase(storeId, it) }
    }

    private fun verifyPurchase(storeId: String, purchase: Purchase) = viewModelScope.launch {
        _ui.update { it.copy(billingBusy = true, message = null) }
        try {
            cloud.verifyPlayPurchase(storeId, purchase.purchaseToken)
            val plan = cloud.planSnapshot()
            _ui.update { it.copy(billingBusy = false, plan = plan, message = "Google Play verified your Pro subscription.") }
        } catch (e: Exception) {
            _ui.update {
                it.copy(
                    billingBusy = false,
                    message = safeCloudError(e, "Server verification failed. You can retry from this screen."),
                )
            }
        }
    }

    private fun accountHash(storeId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(storeId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun billingPeriodLabel(period: String): String {
        val match = Regex("P(\\d+)([DWMY])").matchEntire(period) ?: return period
        val count = match.groupValues[1].toIntOrNull() ?: return period
        val unit = when (match.groupValues[2]) {
            "D" -> "day"
            "W" -> "week"
            "M" -> "month"
            else -> "year"
        }
        return "every $count $unit" + if (count == 1) "" else "s"
    }

    fun authenticate(identifier: String, password: String, createAccount: Boolean, confirmPassword: String = password) {
        val trimmedIdentifier = identifier.trim()
        val isEmail = trimmedIdentifier.contains("@")
        if (trimmedIdentifier.isBlank() || password.length < 8) {
            _ui.update { it.copy(message = "Enter your email or mobile number and a password with at least 8 characters.") }
            return
        }
        if (createAccount && !Patterns.EMAIL_ADDRESS.matcher(trimmedIdentifier).matches()) {
            _ui.update { it.copy(message = "Enter a valid email address for your cloud account.") }
            return
        }
        if (createAccount && password != confirmPassword) {
            _ui.update { it.copy(message = "Passwords do not match.") }
            return
        }
        if (!createAccount && !isEmail && !trimmedIdentifier.matches(Regex("^\\+[1-9]\\d{7,14}$"))) {
            _ui.update { it.copy(message = "Enter your mobile number with country code, for example +919876543210.") }
            return
        }
        if (createAccount && !isEmail) {
            _ui.update { it.copy(message = "Create an account with an email address. You can sign in with a mobile number if it is enabled on your account.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                if (createAccount) {
                cloud.signUp(trimmedIdentifier, password)
                    val signedIn = cloud.signedInIdentifier()
                    _ui.value = CloudAccountUiState(
                        accountIdentifier = signedIn,
                        message = if (signedIn == null) "Account created. Verify your email, then sign in." else "Account created. Connect this store to continue.",
                        awaitingEmailConfirmation = signedIn == null,
                    )
                    refreshPlan()
                } else {
                    if (isEmail) cloud.signIn(trimmedIdentifier, password)
                    else cloud.signInWithPhone(trimmedIdentifier, password)
                    _ui.value = CloudAccountUiState(
                        accountIdentifier = cloud.signedInIdentifier(),
                        message = "Signed in. Connect this store to continue.",
                    )
                    refreshPlan()
                }
            } catch (e: Exception) {
                _ui.value = CloudAccountUiState(
                    accountIdentifier = cloud.signedInIdentifier(),
                    message = CloudAuthError.message(e)
                        ?: safeCloudError(
                            e,
                            "Cloud authentication failed. Check your credentials and try again.",
                            emailRedirectRequest = createAccount,
                        ),
                )
            }
        }
    }

    fun sendPasswordReset(identifier: String) = viewModelScope.launch {
        val email = identifier.trim()
        if (!email.contains("@")) {
            _ui.update {
                it.copy(message = "Password reset is available by email. Enter the email linked to your account.")
            }
            return@launch
        }
        _ui.update { it.copy(busy = true, message = null) }
        try {
            cloud.sendPasswordReset(email)
            _ui.update {
                it.copy(busy = false, message = "If an account exists for this email, a password reset link has been sent.")
            }
        } catch (error: Exception) {
            _ui.update {
                it.copy(
                    busy = false,
                    message = CloudAuthError.message(error)
                        ?: safeCloudError(error, "Could not send the password reset email. Try again later.", emailRedirectRequest = true),
                )
            }
        }
    }

    fun resendConfirmation(email: String) = viewModelScope.launch {
        if (email.isBlank()) {
            _ui.update { it.copy(message = "Enter the email address used to create the account.") }
            return@launch
        }
        _ui.update { it.copy(busy = true, message = null) }
        try {
            cloud.resendSignupConfirmation(email)
            _ui.update {
                it.copy(
                    busy = false,
                    awaitingEmailConfirmation = true,
                    message = "A fresh verification link is on its way. Check your inbox and spam folder, then tap the link to verify your email.",
                )
            }
        } catch (e: Exception) {
            _ui.update {
                it.copy(
                    busy = false,
                    message = CloudAuthError.message(e)
                        ?: safeCloudError(e, "Could not resend the confirmation email. Try again later.", emailRedirectRequest = true),
                )
            }
        }
    }

    fun refreshSession() {
        val identifier = cloud.signedInIdentifier()
        _ui.update {
            it.copy(
                accountIdentifier = identifier,
                awaitingEmailConfirmation = if (identifier == null) it.awaitingEmailConfirmation else false,
            )
        }
        if (identifier != null) refreshPlan()
    }

    fun clearMessageForAuthentication() {
        _ui.update { it.copy(message = null) }
    }

    fun connect() = runCloudAction(cloud::connectAndSync)
    fun joinStore() = runCloudAction(cloud::joinInvitedStore)
    fun syncNow() = runCloudAction(cloud::syncNow)

    fun signOut() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        try {
            cloud.signOut()
            WorkManager.getInstance(context).cancelUniqueWork(CloudSyncWorker.WORK_NAME)
            _ui.value = CloudAccountUiState(message = "Signed out. Local store data remains on this phone.")
        } catch (e: Exception) {
            _ui.value = CloudAccountUiState(
                accountIdentifier = cloud.signedInIdentifier(),
                message = safeCloudError(e, "Could not sign out. Try again."),
            )
        }
    }

    private fun runCloudAction(action: suspend () -> com.xcloak.storevia.data.cloud.CloudSyncSummary) = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        try {
            val result = action()
            scheduleBackgroundSync()
            val plan = cloud.planSnapshot()
            val isPlatformAdmin = cloud.isPlatformAdmin()
            _ui.value = CloudAccountUiState(
                accountIdentifier = cloud.signedInIdentifier(),
                message = "Sync complete · ${result.pushed} sent · ${result.pulled} received",
                plan = plan,
                platformAdmin = isPlatformAdmin,
            )
            refreshExistingPurchases()
        } catch (e: Exception) {
            _ui.value = CloudAccountUiState(
                accountIdentifier = cloud.signedInIdentifier(),
                message = safeCloudError(e, "Cloud operation failed. Check your connection and try again."),
            )
        }
    }

    private fun safeCloudError(
        error: Throwable,
        fallback: String,
        emailRedirectRequest: Boolean = false,
    ): String {
        CloudAuthError.message(error)?.let { return it }
        val messages = generateSequence(error) { it.cause }.take(16).mapNotNull { it.message }.toList()
        if (messages.any {
                it.contains("over_email_send_rate_limit", ignoreCase = true) ||
                    it.contains("email rate limit", ignoreCase = true)
            }
        ) {
            return "Supabase rate-limited verification emails. Wait a few minutes before requesting another."
        }
        if (messages.any {
                it.contains("smtp", ignoreCase = true) ||
                    it.contains("email provider", ignoreCase = true) ||
                    it.contains("email sending is not enabled", ignoreCase = true)
            }
        ) {
            return "Supabase could not send the verification email. Check Authentication → SMTP Settings and the Supabase Auth logs."
        }
        if (emailRedirectRequest && messages.any {
                it.contains("redirect url", ignoreCase = true) ||
                    it.contains("redirect_to", ignoreCase = true)
            }
        ) {
            return "Supabase rejected the verification redirect. Add storevia://auth/callback to Authentication → URL Configuration → Redirect URLs."
        }
        if (messages.any {
                it.contains("invalid api key", ignoreCase = true) ||
                    it.contains("invalid jwt", ignoreCase = true) ||
                    it.contains("http 401", ignoreCase = true) ||
                    it.contains("http 403", ignoreCase = true)
            }
        ) {
            return "The cloud project rejected the email request. Verify the Supabase project URL and anon key in the app configuration."
        }
        val resendStatus = messages.firstNotNullOfOrNull { message ->
            Regex("Confirmation resend failed \\(HTTP (\\d{3})\\)", RegexOption.IGNORE_CASE)
                .find(message)?.groupValues?.getOrNull(1)
        }
        if (resendStatus != null) {
            return "Supabase rejected the verification email request (HTTP $resendStatus). Check Authentication → Logs for the exact reason."
        }
        if (messages.any {
                it.contains("schema cache", ignoreCase = true) ||
                    it.contains("could not find the function", ignoreCase = true)
            }
        ) {
            return "Cloud setup needs an update. Apply the latest supabase/schema.sql in the Supabase SQL Editor, then try again."
        }
        if (messages.any { it.contains("subscriptions_plan_check", ignoreCase = true) }) {
            return "Store creation failed because Supabase has an outdated store-creation function. Re-run the latest supabase/schema.sql, then retry."
        }

        val knownLocalErrors = setOf(
            "Sign in to your cloud account first.",
            "Finish setting up your store before enabling cloud sync.",
            "This account is linked to a different store than before. Local data was not changed.",
            "This account belongs to a different store. Back up this phone and restore that store's data before connecting. Local data was not changed.",
            "Connect this store before syncing.",
            "No local store is set up.",
            "No store invitation found for this account.",
        )
        return (error as? IllegalStateException)?.message
            ?.takeIf { it in knownLocalErrors }
            ?: fallback
    }

    private fun refreshPlan() = viewModelScope.launch {
        val plan = runCatching { cloud.planSnapshot() }.getOrNull()
        val isPlatformAdmin = runCatching { cloud.isPlatformAdmin() }.getOrDefault(false)
        _ui.update { it.copy(plan = plan, platformAdmin = isPlatformAdmin) }
    }

    private fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CloudSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun onCleared() {
        if (billingClient.isReady) billingClient.endConnection()
        super.onCleared()
    }
}