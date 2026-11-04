package com.xcloak.storevia.data.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xcloak.storevia.core.database.dao.BatchDao
import com.xcloak.storevia.core.database.dao.BranchDao
import com.xcloak.storevia.core.database.dao.ReportDao
import com.xcloak.storevia.data.prefs.AppPrefs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlertsEntryPoint {
    fun reportDao(): ReportDao
    fun batchDao(): BatchDao
    fun branchDao(): BranchDao
    fun prefs(): AppPrefs
}

/** Runs once a day at the hour the owner chose. Says nothing when there is nothing to report. */
class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val ep = EntryPointAccessors.fromApplication(applicationContext, AlertsEntryPoint::class.java)
        val settings = ep.prefs().settings.value
        if (settings.alertsEnabled) {
            val today = LocalDate.now()
            val branchId = ep.branchDao().selectedId() ?: ep.branchDao().firstId() ?: "default"
            val expiring = ep.reportDao().expiryBetween(today, today.plusDays(settings.expiryWarnDays.toLong()), branchId).first()
            val expired = ep.reportDao().expiryBetween(LocalDate.of(1970, 1, 1), today.minusDays(1), branchId).first()
            val low = ep.batchDao().observeLowStock(branchId).first()
            val plan = AlertPlan(expiring.batches, expiring.costPaise, expired.batches, expired.costPaise, low.size)
            if (!plan.isEmpty) AlertNotifier.show(applicationContext, plan, settings.expiryWarnDays)
        }
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
