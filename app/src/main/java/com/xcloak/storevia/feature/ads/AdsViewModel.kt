package com.xcloak.storevia.feature.ads

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.data.ads.AdsManager
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdsUiState(
    val showAds: Boolean = false,
    val rewardActive: Boolean = false,
    val rewardBusy: Boolean = false,
    val rewardError: String? = null,
    val rewardExpiresAt: String? = null,
    val eligibilityCheckFailed: Boolean = false,
)

@HiltViewModel
class AdsViewModel @Inject constructor(
    private val adsManager: AdsManager,
    private val cloud: CloudAccountRepository,
    private val prefs: AppPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(AdsUiState())
    val state: StateFlow<AdsUiState> = _state.asStateFlow()
    val settings = prefs.settings
    val canRequestAds = adsManager.canRequestAds
    val bannerAdsManager get() = adsManager

    fun showAppOpenIfReady(activity: Activity) = adsManager.showAppOpenIfReady(activity)

    fun refresh() = viewModelScope.launch {
        try {
            when (prefs.settings.value.storageMode) {
                StorageMode.LOCAL -> {
                    val expiry = adsManager.localRewardExpiresAt()
                    _state.value = AdsUiState(
                        showAds = expiry <= System.currentTimeMillis(),
                        rewardActive = expiry > System.currentTimeMillis(),
                        rewardExpiresAt = expiry.takeIf { it > System.currentTimeMillis() }?.toString(),
                    )
                }
                StorageMode.CLOUD -> {
                    val plan = cloud.planSnapshot()
                    val hasPro = plan?.hasActiveProAccess() == true
                    _state.value = AdsUiState(
                        showAds = !hasPro,
                        rewardActive = plan?.rewardedExpiresAt?.let(::isFuture) == true,
                        rewardExpiresAt = plan?.rewardedExpiresAt,
                    )
                }
                null -> _state.value = AdsUiState()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Could not verify ad eligibility", error)
            _state.value = AdsUiState(eligibilityCheckFailed = true)
        }
    }

    fun watchRewardedAd(activity: Activity) {
        if (_state.value.rewardBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(rewardBusy = true, rewardError = null)
            val storageMode = prefs.settings.value.storageMode
            val customData = try {
                if (storageMode == StorageMode.CLOUD) cloud.requestRewardedProCustomData() else null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    rewardBusy = false,
                    rewardError = error.message ?: "Could not prepare the rewarded ad. Try again.",
                )
                return@launch
            }

            adsManager.showRewarded(activity, customData) { earned ->
                viewModelScope.launch {
                    if (!earned) {
                        _state.value = _state.value.copy(
                            rewardBusy = false,
                            rewardError = "The ad did not finish, so temporary Pro access was not granted.",
                        )
                        return@launch
                    }
                    if (storageMode == StorageMode.LOCAL) {
                        try {
                            adsManager.grantLocalReward()
                            refresh().join()
                            _state.value = _state.value.copy(
                                rewardBusy = false,
                                rewardError = null,
                            )
                        } catch (error: Exception) {
                            _state.value = _state.value.copy(
                                rewardBusy = false,
                                rewardError = error.message ?: "Could not save temporary access.",
                            )
                        }
                        return@launch
                    }
                    if (storageMode != StorageMode.CLOUD) {
                        _state.value = _state.value.copy(
                            rewardBusy = false,
                            rewardError = "Connect a store before earning temporary Pro access.",
                        )
                        return@launch
                    }
                    var activated = false
                    repeat(30) {
                        delay(TimeUnit.SECONDS.toMillis(1))
                        val plan = try {
                            cloud.planSnapshot()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            _state.value = _state.value.copy(
                                rewardBusy = false,
                                rewardError = error.message ?: "Could not check the Cloud reward yet.",
                            )
                            return@launch
                        }
                        if (plan?.rewardedExpiresAt?.let(::isFuture) == true) {
                            activated = true
                            _state.value = _state.value.copy(
                                showAds = false,
                                rewardActive = true,
                                rewardBusy = false,
                                rewardError = null,
                                rewardExpiresAt = plan.rewardedExpiresAt,
                            )
                            return@launch
                        }
                    }
                    if (!activated) {
                        _state.value = _state.value.copy(
                            rewardBusy = false,
                            rewardError = "Your ad finished. Cloud verification is still pending; refresh this screen shortly.",
                        )
                        refresh()
                    }
                }
            }
        }
    }

    private fun isFuture(value: String): Boolean =
        runCatching { java.time.Instant.parse(value).isAfter(java.time.Instant.now()) }.getOrDefault(false)

    private companion object {
        const val TAG = "AdsViewModel"
    }
}
