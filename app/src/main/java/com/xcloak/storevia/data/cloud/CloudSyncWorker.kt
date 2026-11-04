package com.xcloak.storevia.data.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudSyncEntryPoint {
    fun cloudAccountRepository(): CloudAccountRepository
}

class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, CloudSyncEntryPoint::class.java)
            .cloudAccountRepository()
        if (!repository.isConfigured || !repository.hasSignedInSession()) return Result.success()
        return try {
            repository.syncNow()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object { const val WORK_NAME = "cloud_sync" }
}