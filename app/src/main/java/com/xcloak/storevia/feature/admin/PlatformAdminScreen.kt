package com.xcloak.storevia.feature.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import java.time.format.DateTimeParseException
import java.text.DateFormat
import java.time.Instant
import java.util.Date

private enum class AccountFilter { ALL, PRO, FREE, ACTIVE, INACTIVE, UNLINKED }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlatformAdminScreen(
    onSignOut: () -> Unit,
    vm: PlatformAdminViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onSignOut)
    val ui by vm.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AccountFilter.ALL) }
    val accounts = ui.overview?.accounts.orEmpty()
    val proCount = accounts.count { account ->
        account.subscriptions.any { it.plan.equals("pro", ignoreCase = true) && it.status.equals("active", ignoreCase = true) }
    }
    val freeCount = accounts.count { account ->
        account.subscriptions.any { !it.plan.equals("pro", ignoreCase = true) && it.status.equals("active", ignoreCase = true) }
    }
    val activeCount = accounts.count { account -> account.subscriptions.any { it.status.equals("active", ignoreCase = true) } }
    val inactiveCount = accounts.count { account -> account.subscriptions.any { !it.status.equals("active", ignoreCase = true) } }
    val linkedCount = accounts.count { it.subscriptions.isNotEmpty() }
    val normalizedQuery = query.trim()
    val filteredAccounts = accounts.filter { account ->
        val matchesQuery = normalizedQuery.isEmpty() || account.email.contains(normalizedQuery, ignoreCase = true)
        val matchesFilter = when (filter) {
            AccountFilter.ALL -> true
            AccountFilter.PRO -> account.subscriptions.any {
                it.plan.equals("pro", ignoreCase = true) && it.status.equals("active", ignoreCase = true)
            }
            AccountFilter.FREE -> account.subscriptions.any {
                !it.plan.equals("pro", ignoreCase = true) && it.status.equals("active", ignoreCase = true)
            }
            AccountFilter.ACTIVE -> account.subscriptions.any { it.status.equals("active", ignoreCase = true) }
            AccountFilter.INACTIVE -> account.subscriptions.any { !it.status.equals("active", ignoreCase = true) }
            AccountFilter.UNLINKED -> account.subscriptions.isEmpty()
        }
        matchesQuery && matchesFilter
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.platform_admin_title))
                        Text(stringResource(R.string.platform_admin_console_subtitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onSignOut, enabled = !ui.busy) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.cloud_sign_out))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !ui.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.platform_admin_refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ui.busy && ui.overview == null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (ui.accessDenied) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            ui.message.orEmpty(),
                            Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            } else if (ui.serviceUpdateRequired) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            stringResource(R.string.platform_admin_service_update_required),
                            Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            } else if (ui.overview != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.platform_admin_accounts_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(stringResource(R.string.platform_admin_accounts_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryCard(stringResource(R.string.platform_admin_metric_accounts), accounts.size.toString(), Modifier.weight(1f), Icons.Filled.People)
                        SummaryCard(stringResource(R.string.platform_admin_metric_pro), proCount.toString(), Modifier.weight(1f), Icons.Filled.WorkspacePremium)
                        SummaryCard(stringResource(R.string.platform_admin_metric_free), freeCount.toString(), Modifier.weight(1f), Icons.Filled.Cloud)
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text(stringResource(R.string.platform_admin_search_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AccountFilter.entries.forEach { option ->
                            val label = when (option) {
                                AccountFilter.ALL -> stringResource(R.string.platform_admin_filter_all, accounts.size)
                                AccountFilter.PRO -> stringResource(R.string.platform_admin_filter_pro, proCount)
                                AccountFilter.FREE -> stringResource(R.string.platform_admin_filter_free, freeCount)
                                AccountFilter.ACTIVE -> stringResource(R.string.platform_admin_filter_active, activeCount)
                                AccountFilter.INACTIVE -> stringResource(R.string.platform_admin_filter_inactive, inactiveCount)
                                AccountFilter.UNLINKED -> stringResource(R.string.platform_admin_filter_unlinked, accounts.size - linkedCount)
                            }
                            FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(label) })
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.platform_admin_account_list), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.platform_admin_result_count, filteredAccounts.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (filteredAccounts.isEmpty()) {
                    item {
                        EmptyAccountsState(
                            hasAccounts = accounts.isNotEmpty(),
                            onClear = { query = ""; filter = AccountFilter.ALL },
                        )
                    }
                } else {
                    items(filteredAccounts, key = { it.userId }) { account ->
                        AccountCard(account = account, busy = ui.busy, onSetPlan = vm::setPlan)
                    }
                }
            }
            if (ui.busy && ui.overview != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!ui.accessDenied) ui.message?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountCard(account: AdminCloudAccount, busy: Boolean, onSetPlan: (String, String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        account.email.ifBlank { stringResource(R.string.platform_admin_email_unavailable) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.platform_admin_account_created, formatAdminDate(account.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StateBadge(
                    text = stringResource(if (account.subscriptions.isEmpty()) R.string.platform_admin_unlinked else R.string.platform_admin_linked),
                    highlighted = account.subscriptions.isNotEmpty(),
                )
            }
            HorizontalDivider()
            if (account.subscriptions.isEmpty()) {
                Text(stringResource(R.string.platform_admin_no_subscriptions), style = MaterialTheme.typography.bodyMedium)
            } else {
                account.subscriptions.forEachIndexed { index, subscription ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                StateBadge(text = subscription.plan.uppercase(), highlighted = subscription.plan.equals("pro", ignoreCase = true))
                            }
                            subscription.expiresAt?.let {
                                Text(
                                    stringResource(R.string.platform_admin_subscription_expires, formatAdminDate(it)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (subscription.status.equals("active", ignoreCase = true)) {
                                if (subscription.plan.equals("pro", ignoreCase = true)) {
                                    OutlinedButton(
                                        onClick = { onSetPlan(subscription.storeId, "free") },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.platform_admin_disable_pro)) }
                                } else {
                                    Button(
                                        onClick = { onSetPlan(subscription.storeId, "pro") },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun StateBadge(text: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyAccountsState(hasAccounts: Boolean, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(if (hasAccounts) R.string.platform_admin_no_matching_accounts else R.string.platform_admin_no_accounts),
                style = MaterialTheme.typography.titleMedium,
            )
            if (hasAccounts) OutlinedButton(onClick = onClear) { Text(stringResource(R.string.platform_admin_clear_filters)) }
        }
    }
}

private fun formatAdminDate(value: String): String =
    try {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(Instant.parse(value)))
    } catch (_: DateTimeParseException) {
        value
    }
