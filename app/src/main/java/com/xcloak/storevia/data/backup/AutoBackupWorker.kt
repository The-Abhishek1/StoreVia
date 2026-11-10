package com.xcloak.storevia.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xcloak.storevia.data.prefs.AppPrefs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupEntryPoint {
    fun backupManager(): BackupManager
    fun prefs(): AppPrefs
}

/** Daily local safety copy. Keeps the newest 7. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java)
        if (shouldCreateLocalAutoBackup(entryPoint.prefs().settings.value.storageMode)) {
            entryPoint.backupManager().autoBackup()
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
