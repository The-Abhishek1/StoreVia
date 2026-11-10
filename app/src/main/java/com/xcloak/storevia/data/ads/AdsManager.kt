package com.xcloak.storevia.data.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.xcloak.storevia.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AdsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val consentInitializationStarted = AtomicBoolean(false)
    private val mobileAdsInitializationStarted = AtomicBoolean(false)
    private val rewardedPrefs = context.getSharedPreferences("ad_reward_access", Context.MODE_PRIVATE)
    private val _canRequestAds = MutableStateFlow(false)
    private val _privacyOptionsRequired = MutableStateFlow(false)
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoading = false
    private var appOpenShown = false
    private var pendingAppOpenActivity: WeakReference<Activity>? = null

    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoading = false
    private var lastInterstitialAt = 0L

    fun requestConsent(activity: Activity) {
        if (!consentInitializationStarted.compareAndSet(false, true)) return
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val parameters = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updateConsent(consentInformation)
                }
            },
            { error ->
                Log.w(TAG, "Consent info update failed: ${error.message}")
                updateConsent(consentInformation)
            },
        )
    }

    fun showPrivacyOptions(activity: Activity) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "Privacy options form failed: ${error.message}")
            updateConsent(consentInformation)
        }
    }

    private fun updateConsent(consentInformation: ConsentInformation) {
        _privacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        val canRequest = consentInformation.canRequestAds()
        _canRequestAds.value = canRequest
        if (canRequest && mobileAdsInitializationStarted.compareAndSet(false, true)) {
            MobileAds.initialize(context) {
                loadInterstitial()
            }
        } else if (canRequest) {
            loadInterstitial()
        }
    }

    fun bannerUnitId(): String = BuildConfig.ADMOB_BANNER_UNIT_ID

    fun showAppOpenIfReady(activity: Activity) {
        if (!_canRequestAds.value || appOpenShown) return
        val loaded = appOpenAd
        if (loaded != null) {
            appOpenAd = null
            appOpenShown = true
            loaded.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() = Unit
                override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                    Log.w(TAG, "App-open ad failed to show: ${error.message}")
                }
            }
            loaded.show(activity)
            return
        }
        if (appOpenLoading) return
        appOpenLoading = true
        pendingAppOpenActivity = WeakReference(activity)
        AppOpenAd.load(
            context,
            BuildConfig.ADMOB_APP_OPEN_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenLoading = false
                    appOpenAd = ad
                    val target = pendingAppOpenActivity?.get()
                    pendingAppOpenActivity = null
                    if (
                        target is LifecycleOwner &&
                        target.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        showAppOpenIfReady(target)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenLoading = false
                    pendingAppOpenActivity = null
                    Log.w(TAG, "App-open ad failed to load: ${error.message}")
                }
            },
        )
    }

    fun maybeShowInterstitial(activity: Activity) {
        if (!_canRequestAds.value) return
        val now = System.currentTimeMillis()
        if (now - lastInterstitialAt < INTERSTITIAL_COOLDOWN_MS) return
        val loaded = interstitialAd
        if (loaded == null) {
            loadInterstitial()
            return
        }
        interstitialAd = null
        lastInterstitialAt = now
        loaded.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = loadInterstitial()
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                Log.w(TAG, "Interstitial ad failed to show: ${error.message}")
                loadInterstitial()
            }
        }
        loaded.show(activity)
    }

    fun showRewarded(
        activity: Activity,
        customData: String?,
        onFinished: (earned: Boolean) -> Unit,
    ) {
        if (!_canRequestAds.value) {
            onFinished(false)
            return
        }
        RewardedAd.load(
            context,
            BuildConfig.ADMOB_REWARDED_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    if (!customData.isNullOrBlank()) {
                        ad.setServerSideVerificationOptions(
                            ServerSideVerificationOptions.Builder()
                                .setCustomData(customData)
                                .build(),
                        )
                    }
                    var earned = false
                    var completed = false
                    fun finishOnce() {
                        if (!completed) {
                            completed = true
                            onFinished(earned)
                        }
                    }
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() = finishOnce()
                        override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                            Log.w(TAG, "Rewarded ad failed to show: ${error.message}")
                            finishOnce()
                        }
                    }
                    ad.show(activity) { earned = true }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Rewarded ad failed to load: ${error.message}")
                    onFinished(false)
                }
            },
        )
    }

    fun localRewardExpiresAt(): Long = rewardedPrefs.getLong(KEY_LOCAL_REWARD_EXPIRY, 0L)

    fun grantLocalReward() {
        val expiry = maxOf(localRewardExpiresAt(), System.currentTimeMillis()) + REWARD_DURATION_MS
        check(rewardedPrefs.edit().putLong(KEY_LOCAL_REWARD_EXPIRY, expiry).commit()) {
            "Could not save temporary Pro access on this device."
        }
    }

    private fun loadInterstitial() {
        if (!_canRequestAds.value || interstitialLoading || interstitialAd != null) return
        interstitialLoading = true
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    interstitialAd = null
                    Log.w(TAG, "Interstitial ad failed to load: ${error.message}")
                }
            },
        )
    }

    private companion object {
        const val TAG = "AdsManager"
        const val KEY_LOCAL_REWARD_EXPIRY = "pro_reward_expires_at"
        const val REWARD_DURATION_MS = 15 * 60 * 1000L
        const val INTERSTITIAL_COOLDOWN_MS = 15 * 60 * 1000L
    }
}
