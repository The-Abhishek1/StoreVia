package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.StorageMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class CloudSyncStatus {
    IDLE,
    SYNCING,
    FAILED,
}

@Singleton
class CloudSyncCoordinator @Inject constructor(
    private val prefs: AppPrefs,
    private val cloud: CloudAccountRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(CloudSyncStatus.IDLE)
    val status: StateFlow<CloudSyncStatus> = _status.asStateFlow()

    fun start() = Unit

    suspend fun synchronize(): CloudSyncSummary? {
        if (!isCloudMode() || !cloud.hasSignedInSession()) {
            _status.value = CloudSyncStatus.IDLE
            return null
        }
        _status.value = CloudSyncStatus.SYNCING
        return try {
            cloud.syncNow().also { _status.value = CloudSyncStatus.IDLE }
        } catch (e: CancellationException) {
            _status.value = CloudSyncStatus.IDLE
            throw e
        } catch (e: Exception) {
            _status.value = CloudSyncStatus.FAILED
            null
        }
    }

    fun retry() {
        scope.launch { synchronize() }
    }

    private fun isCloudMode(): Boolean {
        return prefs.settings.value.storageMode == StorageMode.CLOUD
    }
}
