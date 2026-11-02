package com.xcloak.storevia

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xcloak.storevia.data.alerts.AlertNotifier
import com.xcloak.storevia.data.alerts.AlertScheduler
import com.xcloak.storevia.data.backup.AutoBackupWorker
import com.xcloak.storevia.data.prefs.AppPrefs
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class StoreviaApp : Application() {
    @Inject lateinit var prefs: AppPrefs

    override fun onCreate() {
        super.onCreate()
        AlertNotifier.createChannel(this)
        AlertScheduler.schedule(this, prefs.settings.value, reschedule = false)

        val backup = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("auto_backup", ExistingPeriodicWorkPolicy.KEEP, backup)
    }
}
