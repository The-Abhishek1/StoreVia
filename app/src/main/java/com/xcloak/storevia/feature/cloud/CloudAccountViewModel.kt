package com.xcloak.storevia.feature.cloud

import android.app.Activity
import android.content.Context
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

data class CloudAccountUiState(
    val email: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val plan: CloudPlanSnapshot? = null,
    val billingReady: Boolean = false,
    val billingBusy: Boolean = false,
    val proPrice: String? = null,
    val proBillingPeriod: String? = null,
    val platformAdmin: Boolean = false,
)

@HiltViewModel
class CloudAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloud: CloudAccountRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _ui = MutableStateFlow(CloudAccountUiState(email = cloud.signedInEmail()))
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
            _ui.update { it.copy(billingBusy = false, message = e.message ?: "Server verification failed. You can retry from this screen.") }
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

    fun authenticate(email: String, password: String, createAccount: Boolean) {
        if (email.isBlank() || password.length < 8) {
            _ui.update { it.copy(message = "Enter a valid email and a password with at least 8 characters.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = null) }
            try {
                if (createAccount) {
                    cloud.signUp(email, password)
                    val signedIn = cloud.signedInEmail()
                    _ui.value = CloudAccountUiState(
                        email = signedIn,
                        message = if (signedIn == null) "Account created. Verify your email, then sign in." else "Account created. Connect this store to continue.",
                    )
                    refreshPlan()
                } else {
                    cloud.signIn(email, password)
                    _ui.value = CloudAccountUiState(email = cloud.signedInEmail(), message = "Signed in. Connect this store to continue.")
                    refreshPlan()
                }
            } catch (e: Exception) {
                _ui.value = CloudAccountUiState(email = cloud.signedInEmail(), message = e.message ?: "Cloud authentication failed.")
            }
        }
    }

    fun connect() = runCloudAction(cloud::connectAndSync)
    fun syncNow() = runCloudAction(cloud::syncNow)

    fun signOut() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, message = null) }
        try {
            cloud.signOut()
            WorkManager.getInstance(context).cancelUniqueWork(CloudSyncWorker.WORK_NAME)
            _ui.value = CloudAccountUiState(message = "Signed out. Local store data remains on this phone.")
        } catch (e: Exception) {
            _ui.value = CloudAccountUiState(email = cloud.signedInEmail(), message = e.message ?: "Could not sign out.")
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
                email = cloud.signedInEmail(),
                message = "Sync complete · ${result.pushed} sent · ${result.pulled} received",
                plan = plan,
                platformAdmin = isPlatformAdmin,
            )
            refreshExistingPurchases()
        } catch (e: Exception) {
            _ui.value = CloudAccountUiState(email = cloud.signedInEmail(), message = e.message ?: "Cloud operation failed.")
        }
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