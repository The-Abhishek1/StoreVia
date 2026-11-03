package com.xcloak.storevia.feature.settings

import com.xcloak.storevia.core.access.*
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.xcloak.storevia.data.alerts.AlertNotifier
import com.xcloak.storevia.data.alerts.AlertPlan
import com.xcloak.storevia.data.alerts.AlertScheduler
import com.xcloak.storevia.data.prefs.AppPrefs
import com.xcloak.storevia.data.prefs.AppSettings
import com.xcloak.storevia.core.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
    private val access: Access,
    private val audit: AuditSink,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = prefs.settings

    fun setAlerts(on: Boolean) {
        prefs.update { copy(alertsEnabled = on) }
        AlertScheduler.schedule(context, prefs.settings.value, reschedule = true)
    }

    fun setExpiryDays(days: Int) = prefs.update { copy(expiryWarnDays = days) }
    fun setThemeMode(mode: ThemeMode) = prefs.update { copy(themeMode = mode) }

    fun setAlertHour(hour: Int) {
        prefs.update { copy(alertHour = hour) }
        AlertScheduler.schedule(context, prefs.settings.value, reschedule = true)
    }

    fun setLock(on: Boolean) {
        if (!access.actor().can(Permission.MANAGE_SETTINGS)) return
        prefs.update { copy(lockEnabled = on) }
        viewModelScope.launch { audit.log(AuditAction.SETTINGS_CHANGED, "App lock turned ${if (on) "on" else "off"}") }
    }
    fun setLockGrace(seconds: Int) = prefs.update { copy(lockGraceSeconds = seconds) }

    /** Lets the owner check that notifications work, without waiting for tomorrow morning. */
    fun sendTestAlert() = AlertNotifier.show(
        context,
        AlertPlan(expiringBatches = 3, expiringCostPaise = 125_000, expiredBatches = 1, expiredCostPaise = 40_000, lowStockProducts = 5),
        prefs.settings.value.expiryWarnDays,
    )
}
