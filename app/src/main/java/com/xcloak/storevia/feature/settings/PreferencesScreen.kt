package com.xcloak.storevia.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.security.DeviceAuth
import com.xcloak.storevia.data.alerts.AlertNotifier
import com.xcloak.storevia.core.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PreferencesScreen(onBack: () -> Unit, vm: PreferencesViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var notificationsOn by remember { mutableStateOf(AlertNotifier.canNotify(context)) }
    var needScreenLock by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOn = AlertNotifier.canNotify(context) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOn = AlertNotifier.canNotify(context) }
    // Turning the lock on first proves the owner can pass it, so they can't lock themselves out by accident.
    val confirmLock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) vm.setLock(true) }

    val testSent = stringResource(R.string.test_alert_sent)
    val lockPrompt = stringResource(R.string.confirm_lock_prompt)
    var sendTest by remember { mutableStateOf(false) }
    LaunchedEffect(sendTest) { if (sendTest) { snackbar.showSnackbar(testSent); sendTest = false } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prefs_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        label = { Text(stringResource(when (mode) {
                            ThemeMode.SYSTEM -> R.string.theme_system
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                        })) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.section_alerts), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.alerts_switch), stringResource(R.string.alerts_switch_hint), s.alertsEnabled) { on ->
                vm.setAlerts(on)
                if (on && Build.VERSION.SDK_INT >= 33 && !notificationsOn) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (s.alertsEnabled && !notificationsOn) Text(stringResource(R.string.alerts_blocked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (s.alertsEnabled) {
                Text(stringResource(R.string.warn_days_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 15, 30, 60).forEach { d ->
                        FilterChip(s.expiryWarnDays == d, { vm.setExpiryDays(d) }, { Text(stringResource(R.string.days_chip, d)) })
                    }
                }
                Text(stringResource(R.string.alert_time_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(8, 9, 12, 18).forEach { h ->
                        FilterChip(s.alertHour == h, { vm.setAlertHour(h) }, { Text(hourLabel(h)) })
                    }
                }
                OutlinedButton(onClick = { vm.sendTestAlert(); sendTest = true }, enabled = notificationsOn) { Text(stringResource(R.string.send_test_alert)) }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.section_security), style = MaterialTheme.typography.titleMedium)
            SwitchRow(stringResource(R.string.lock_switch), stringResource(R.string.lock_switch_hint), s.lockEnabled) { on ->
                if (!on) { vm.setLock(false); needScreenLock = false }
                else {
                    val intent = DeviceAuth.confirmIntent(context, lockPrompt, null)
                    if (intent == null) needScreenLock = true else { needScreenLock = false; confirmLock.launch(intent) }
                }
            }
            if (needScreenLock) Text(stringResource(R.string.lock_needs_screen_lock), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (s.lockEnabled) {
                Text(stringResource(R.string.lock_after_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 60, 300, 900).forEach { sec ->
                        FilterChip(s.lockGraceSeconds == sec, { vm.setLockGrace(sec) }, {
                            Text(if (sec < 60) stringResource(R.string.seconds_chip, sec) else stringResource(R.string.minutes_chip, sec / 60))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun hourLabel(hour: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return stringResource(if (hour < 12) R.string.hour_chip_am else R.string.hour_chip_pm, h12)
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
