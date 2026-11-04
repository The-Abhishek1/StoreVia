package com.xcloak.storevia.data.prefs

import android.content.Context
import com.xcloak.storevia.core.store.StoreType
import com.xcloak.storevia.core.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val alertsEnabled: Boolean = true,
    /** Warn about batches that expire within this many days. */
    val expiryWarnDays: Int = 30,
    /** Local hour (0-23) when the daily stock alert is delivered. */
    val alertHour: Int = 9,
    val lockEnabled: Boolean = false,
    /** App relocks after being in the background this long. */
    val lockGraceSeconds: Int = 60,
    val onboardingDone: Boolean = false,
    val localOnlyMode: Boolean = false,
    val cashierCanDiscount: Boolean = false,
    val cashierCanSellOnCredit: Boolean = false,
    val cashierCanOverrideBatch: Boolean = false,
    val storeType: StoreType = StoreType.GENERAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cloudStoreId: String? = null,
    val cloudRole: String? = null,
    /** Letter that keeps this phone's invoice numbers different from other phones' ("" for the first phone). */
    val deviceSeries: String = "",
)

/** Small settings store (SharedPreferences) exposed as a StateFlow so screens and workers share one source. */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(block: AppSettings.() -> AppSettings) {
        val n = _settings.value.block()
        sp.edit()
            .putBoolean("alerts", n.alertsEnabled).putInt("expiry_days", n.expiryWarnDays).putInt("alert_hour", n.alertHour)
            .putBoolean("lock", n.lockEnabled).putInt("lock_grace", n.lockGraceSeconds)
            .putBoolean("onboarding_done", n.onboardingDone)
            .putBoolean("local_only_mode", n.localOnlyMode)
            .putBoolean("cashier_discount", n.cashierCanDiscount).putBoolean("cashier_credit", n.cashierCanSellOnCredit)
            .putBoolean("cashier_batch_override", n.cashierCanOverrideBatch)
            .putString("store_type", n.storeType.name)
            .putString("theme_mode", n.themeMode.name)
            .putString("cloud_store_id", n.cloudStoreId)
            .putString("cloud_role", n.cloudRole)
            .putString("device_series", n.deviceSeries)
            .apply()
        _settings.value = n
    }

    /**
     * A restore replaces the whole database, so its audit entry can't be written first. It is parked here and
     * written into the restored database the next time the app starts.
     */
    fun setPendingAudit(action: String, summary: String, actorName: String, actorRole: String) {
        sp.edit().putString("pending_audit", listOf(action, summary, actorName, actorRole).joinToString("\u0001")).apply()
    }

    fun takePendingAudit(): List<String>? {
        val raw = sp.getString("pending_audit", null) ?: return null
        sp.edit().remove("pending_audit").apply()
        return raw.split("\u0001").takeIf { it.size == 4 }
    }

    private fun read() = AppSettings(
        alertsEnabled = sp.getBoolean("alerts", true),
        expiryWarnDays = sp.getInt("expiry_days", 30),
        alertHour = sp.getInt("alert_hour", 9),
        lockEnabled = sp.getBoolean("lock", false),
        lockGraceSeconds = sp.getInt("lock_grace", 60),
        onboardingDone = sp.getBoolean("onboarding_done", false),
        localOnlyMode = sp.getBoolean("local_only_mode", false),
        cashierCanDiscount = sp.getBoolean("cashier_discount", false),
        cashierCanSellOnCredit = sp.getBoolean("cashier_credit", false),
        cashierCanOverrideBatch = sp.getBoolean("cashier_batch_override", false),
        storeType = StoreType.fromName(sp.getString("store_type", StoreType.GENERAL.name)),
        themeMode = runCatching { ThemeMode.valueOf(sp.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM),
        cloudStoreId = sp.getString("cloud_store_id", null),
        cloudRole = sp.getString("cloud_role", null),
        deviceSeries = sp.getString("device_series", "") ?: "",
    )
}
