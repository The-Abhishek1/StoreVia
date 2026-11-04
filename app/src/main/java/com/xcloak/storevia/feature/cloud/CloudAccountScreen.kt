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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountScreen(
    onBack: () -> Unit,
    adminOnly: Boolean = false,
    onAdminSignOut: (() -> Unit)? = null,
    lockCreateAccount: Boolean = false,
    initialCreateAccount: Boolean = false,
    joinOnly: Boolean = false,
    createOnly: Boolean = false,
    vm: CloudAccountViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val adminSignOutAction: () -> Unit = onAdminSignOut ?: {
        vm.signOut()
        Unit
    }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var createAccount by rememberSaveable(initialCreateAccount, adminOnly, lockCreateAccount) {
        mutableStateOf(initialCreateAccount)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSession() }
    androidx.compose.runtime.LaunchedEffect(adminOnly, lockCreateAccount, initialCreateAccount, joinOnly, createOnly) {
        vm.clearMessageForAuthentication()
    }

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
            if (initialCreateAccount || createOnly) {
                val stage = if (initialCreateAccount) 3 else 4
                Text(
                    stringResource(if (initialCreateAccount) R.string.cloud_signup_step_credentials else R.string.cloud_signup_step_workspace),
                    style = MaterialTheme.typography.labelLarge,
                )
                LinearProgressIndicator(progress = { stage / 4f }, modifier = Modifier.fillMaxWidth())
            }
            if (ui.accountIdentifier != null) {
                Text(stringResource(R.string.cloud_description), style = MaterialTheme.typography.bodyLarge)
            }
            if (!vm.isConfigured) {
                Text(stringResource(R.string.cloud_not_configured), color = MaterialTheme.colorScheme.error)
            } else if (ui.accountIdentifier == null) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(
                                when {
                                    adminOnly -> R.string.platform_admin_login_heading
                                    createAccount -> R.string.cloud_create_heading
                                    else -> R.string.cloud_login_heading
                                },
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(
                                when {
                                    adminOnly -> R.string.platform_admin_login_hint
                                    createAccount -> R.string.cloud_create_hint
                                    else -> R.string.cloud_login_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            if (createAccount) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Icon(
                                            Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(10.dp),
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            stringResource(R.string.cloud_signup_secure_title),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            stringResource(R.string.cloud_signup_secure_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (!adminOnly && !lockCreateAccount) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = !createAccount, onClick = { createAccount = false }, label = { Text(stringResource(R.string.cloud_mode_sign_in)) })
                                    FilterChip(selected = createAccount, onClick = { createAccount = true }, label = { Text(stringResource(R.string.cloud_mode_sign_up)) })
                                }
                            }
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                singleLine = true,
                                label = { Text(stringResource(if (createAccount) R.string.cloud_email else R.string.cloud_login_identifier)) },
                                leadingIcon = { Icon(Icons.Filled.AlternateEmail, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.cloud_password)) },
                                supportingText = if (createAccount) {
                                    { Text(stringResource(R.string.cloud_password_requirements)) }
                                } else {
                                    null
                                },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = stringResource(if (passwordVisible) R.string.cloud_hide_password else R.string.cloud_show_password),
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (createAccount) {
                                OutlinedTextField(
                                    value = confirmPassword,
                                    onValueChange = { confirmPassword = it },
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.cloud_confirm_password)) },
                                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                    trailingIcon = {
                                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                            Icon(
                                                if (confirmPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                contentDescription = stringResource(
                                                    if (confirmPasswordVisible) R.string.cloud_hide_password else R.string.cloud_show_password,
                                                ),
                                            )
                                        }
                                    },
                                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Text(
                                        stringResource(R.string.cloud_signup_verification_hint),
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            if (!createAccount) {
                                TextButton(
                                    onClick = { vm.sendPasswordReset(email) },
                                    enabled = !ui.busy,
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                                ) {
                                    Text(stringResource(R.string.cloud_forgot_password))
                                }
                            }
                            Button(
                                onClick = { vm.authenticate(email, password, createAccount, confirmPassword) },
                                enabled = !ui.busy && (!createAccount || confirmPassword.isNotEmpty()),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                if (ui.busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(stringResource(if (createAccount) R.string.cloud_sign_up else R.string.cloud_sign_in))
                                }
                            }
                            if (createAccount || ui.awaitingEmailConfirmation) {
                                OutlinedButton(onClick = { vm.resendConfirmation(email) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.cloud_resend_confirmation))
                                }
                            }
                        }
                    }
                }
            } else {
                if (createOnly) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Icon(
                                        Icons.Filled.Cloud,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        stringResource(R.string.cloud_workspace_ready_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.cloud_connected_as, ui.accountIdentifier.orEmpty()),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.cloud_workspace_ready_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { if (joinOnly) vm.joinStore() else vm.connect() },
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) {
                                if (ui.busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(stringResource(if (joinOnly) R.string.cloud_join_store else R.string.cloud_create_store))
                                }
                            }
                            OutlinedButton(
                                onClick = vm::signOut,
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.cloud_sign_out))
                            }
                        }
                    }
                } else {
                    Text(stringResource(R.string.cloud_connected_as, ui.accountIdentifier.orEmpty()), style = MaterialTheme.typography.titleMedium)
                }
                if (adminOnly) {
                    if (!ui.platformAdmin) {
                        Text(stringResource(R.string.platform_admin_access_denied), color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(
                        onClick = adminSignOutAction,
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cloud_sign_out))
                    }
                } else {
                if (!createOnly) ui.plan?.let { plan ->
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
                val connectedStoreId = settings.cloudStoreId
                if (!createOnly) {
                    if (connectedStoreId != null && !joinOnly) {
                        val storeId = connectedStoreId
                        Text(stringResource(R.string.cloud_store_binding, storeId, settings.cloudRole ?: "MEMBER"))
                        Button(onClick = vm::syncNow, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(R.string.cloud_sync_now))
                        }
                    } else {
                        Text(stringResource(R.string.cloud_connection_ready), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { if (joinOnly) vm.joinStore() else vm.connect() },
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(stringResource(if (joinOnly) R.string.cloud_join_store else R.string.cloud_connect))
                        }
                    }
                    HorizontalDivider()
                    OutlinedButton(onClick = vm::signOut, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cloud_sign_out))
                    }
                }
                }
            }
            if (ui.busy || ui.billingBusy) Text(stringResource(R.string.working), style = MaterialTheme.typography.bodySmall)
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.height(8.dp))
        }
    }
}