package com.xcloak.storevia.feature.cloud

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountScreen(onBack: () -> Unit, onPlatformAdmin: () -> Unit, vm: CloudAccountViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var createAccount by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.cloud_description), style = MaterialTheme.typography.bodyLarge)
            if (!vm.isConfigured) {
                Text(stringResource(R.string.cloud_not_configured), color = MaterialTheme.colorScheme.error)
            } else if (ui.email == null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !createAccount, onClick = { createAccount = false }, label = { Text(stringResource(R.string.cloud_mode_sign_in)) })
                        FilterChip(selected = createAccount, onClick = { createAccount = true }, label = { Text(stringResource(R.string.cloud_mode_sign_up)) })
                    }
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, singleLine = true,
                        label = { Text(stringResource(R.string.cloud_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text(stringResource(R.string.cloud_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.authenticate(email, password, createAccount) },
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(if (createAccount) R.string.cloud_sign_up else R.string.cloud_sign_in)) }
                }
            } else {
                Text(stringResource(R.string.cloud_connected_as, ui.email.orEmpty()), style = MaterialTheme.typography.titleMedium)
                ui.plan?.let { plan ->
                    val planLabel = stringResource(
                        when (plan.plan.lowercase()) {
                            "pro" -> R.string.cloud_plan_pro
                            else -> R.string.cloud_plan_free
                        },
                    )
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.cloud_plan_status, planLabel, plan.status), style = MaterialTheme.typography.titleSmall)
                            if (plan.plan.equals("pro", ignoreCase = true) && plan.expiresAt != null) {
                                Text(stringResource(R.string.cloud_plan_expires, plan.expiresAt.substringBefore('T')), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (!plan.plan.equals("pro", ignoreCase = true)) {
                        Text(
                            ui.proPrice?.let { stringResource(R.string.cloud_pro_offer, it, ui.proBillingPeriod.orEmpty()) }
                                ?: stringResource(R.string.cloud_pro_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(stringResource(R.string.cloud_pro_renewal_note), style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { activity?.let(vm::buyPro) },
                            enabled = ui.billingReady && !ui.billingBusy && activity != null && settings.cloudStoreId != null,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.cloud_upgrade_pro)) }
                    }
                }
                settings.cloudStoreId?.let { storeId ->
                    Text(stringResource(R.string.cloud_store_binding, storeId, settings.cloudRole ?: "MEMBER"))
                    Button(onClick = vm::syncNow, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.cloud_sync_now))
                    }
                } ?: run {
                    Text(stringResource(R.string.cloud_connection_ready), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = vm::connect, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.cloud_connect))
                    }
                }
                if (ui.platformAdmin) {
                    OutlinedButton(onClick = onPlatformAdmin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.platform_admin_title))
                    }
                }
                HorizontalDivider()
                OutlinedButton(onClick = vm::signOut, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cloud_sign_out))
                }
            }
            if (ui.busy || ui.billingBusy) Text(stringResource(R.string.working), style = MaterialTheme.typography.bodySmall)
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.height(8.dp))
        }
    }
}