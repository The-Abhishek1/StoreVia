package com.xcloak.storevia.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import java.text.DateFormat
import java.time.Instant
import java.util.Date

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlatformAdminScreen(onBack: () -> Unit, vm: PlatformAdminViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.platform_admin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.platform_admin_accounts_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.platform_admin_accounts_hint), style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.accessDenied) {
                item { Text(ui.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            } else if (ui.overview != null) {
                val accounts = ui.overview!!.accounts
                if (accounts.isEmpty()) item { Text(stringResource(R.string.platform_admin_no_accounts)) }
                items(accounts, key = { it.userId }) { account ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(account.email.ifBlank { stringResource(R.string.platform_admin_email_unavailable) }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.platform_admin_account_created, formatAdminDate(account.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (account.subscriptions.isEmpty()) {
                                Text(stringResource(R.string.platform_admin_no_subscriptions), style = MaterialTheme.typography.bodySmall)
                            } else {
                                account.subscriptions.forEachIndexed { index, subscription ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                stringResource(R.string.platform_admin_subscription_label, index + 1),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.platform_admin_subscription_status,
                                                    subscription.plan.uppercase(),
                                                    subscription.status,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        if (subscription.status == "active") {
                                            if (subscription.plan.equals("pro", ignoreCase = true)) {
                                                OutlinedButton(
                                                    onClick = { vm.setPlan(subscription.storeId, "free") },
                                                    enabled = !ui.busy,
                                                ) { Text(stringResource(R.string.platform_admin_disable_pro)) }
                                            } else {
                                                Button(
                                                    onClick = { vm.setPlan(subscription.storeId, "pro") },
                                                    enabled = !ui.busy,
                                                ) { Text(stringResource(R.string.platform_admin_enable_pro)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            ui.message?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

private fun formatAdminDate(value: String): String =
    runCatching {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(Instant.parse(value)))
    }.getOrElse { value }
