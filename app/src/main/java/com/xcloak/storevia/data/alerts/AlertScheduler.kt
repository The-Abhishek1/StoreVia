package com.xcloak.storevia.data.alerts

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xcloak.storevia.data.prefs.AppSettings
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object AlertScheduler {
    private const val NAME = "stock_alerts"

    /**
     * @param reschedule true when the owner just changed the time or switched alerts on: restart the daily timer
     * so the next alert lands at the new hour. False at app start: keep whatever is already scheduled.
     */
    fun schedule(context: Context, settings: AppSettings, reschedule: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!settings.alertsEnabled) { wm.cancelUniqueWork(NAME); return }
        val delay = AlertPlanner.delayUntilNext(LocalDateTime.now(), settings.alertHour)
        val request = PeriodicWorkRequestBuilder<AlertsWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        wm.enqueueUniquePeriodicWork(
            NAME,
            if (reschedule) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
