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
        if (!canManageSettings()) return
        prefs.update { copy(alertsEnabled = on) }
        if (!on) AlertNotifier.clear(context)
        AlertScheduler.schedule(context, prefs.settings.value, reschedule = true)
    }

    fun setExpiryDays(days: Int) {
        if (canManageSettings()) prefs.update { copy(expiryWarnDays = days) }
    }
    fun setThemeMode(mode: ThemeMode) {
        if (canManageSettings()) prefs.update { copy(themeMode = mode) }
    }

    fun setAlertHour(hour: Int) {
        if (!canManageSettings()) return
        prefs.update { copy(alertHour = hour) }
        AlertScheduler.schedule(context, prefs.settings.value, reschedule = true)
    }

    fun setLock(on: Boolean) {
        if (!canManageSettings()) return
        prefs.update { copy(lockEnabled = on) }
        viewModelScope.launch { audit.log(AuditAction.SETTINGS_CHANGED, "App lock turned ${if (on) "on" else "off"}") }
    }
    fun setLockGrace(seconds: Int) {
        if (canManageSettings()) prefs.update { copy(lockGraceSeconds = seconds) }
    }

    /** Lets the owner check that notifications work, without waiting for tomorrow morning. */
    fun sendTestAlert() {
        if (!canManageSettings()) return
        AlertNotifier.show(
            context,
            AlertPlan(expiringBatches = 3, expiringCostPaise = 125_000, expiredBatches = 1, expiredCostPaise = 40_000, lowStockProducts = 5),
            prefs.settings.value.expiryWarnDays,
        )
    }

    private fun canManageSettings() = access.actor().can(Permission.MANAGE_SETTINGS)
}
