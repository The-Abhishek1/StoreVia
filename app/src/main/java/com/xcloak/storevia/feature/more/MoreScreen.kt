package com.xcloak.storevia.feature.more

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.LocalActor
import com.xcloak.storevia.feature.staff.AccessViewModel
import com.xcloak.storevia.feature.staff.roleLabel

private class MenuItem(val icon: ImageVector, val title: Int, val hint: Int, val allowed: (Set<Permission>) -> Boolean, val onClick: () -> Unit)

private const val SUPPORT_EMAIL = "idiot63666@gmail.com"
private const val PLAY_DEVELOPER_ID = "7987641309987531583"

private fun openPlayStore(context: Context, marketUri: String, webUri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marketUri)))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
    }
}

/** Everything that is not a bottom-bar tab. An entry only appears if the signed-in person may use it. */
@Composable
fun MoreScreen(
    onCustomers: () -> Unit, onStoreSettings: () -> Unit, onBackup: () -> Unit, onData: () -> Unit, onPrefs: () -> Unit,
    onCategories: () -> Unit, onSuppliers: () -> Unit, onPurchases: () -> Unit, onReorder: () -> Unit,
    onStaff: () -> Unit, onAudit: () -> Unit, onCloudAccount: () -> Unit,
    privacyOptionsRequired: Boolean = false, onPrivacyOptions: () -> Unit = {},
    access: AccessViewModel = hiltViewModel(),
) {
    val actor = LocalActor.current
    val context = LocalContext.current
    val staffMode by access.staffMode.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val items = listOf(
        MenuItem(Icons.Filled.People, R.string.more_customers, R.string.more_customers_hint, { Permission.MANAGE_CUSTOMERS in it }, onCustomers),
        MenuItem(Icons.Filled.LocalShipping, R.string.more_suppliers, R.string.more_suppliers_hint, { Permission.MANAGE_PURCHASES in it }, onSuppliers),
        MenuItem(Icons.Filled.Receipt, R.string.more_purchases, R.string.more_purchases_hint, { Permission.MANAGE_PURCHASES in it }, onPurchases),
        MenuItem(Icons.Filled.ShoppingCart, R.string.more_reorder, R.string.more_reorder_hint, { Permission.MANAGE_PURCHASES in it }, onReorder),
        MenuItem(Icons.Filled.Category, R.string.more_categories, R.string.more_categories_hint, { Permission.MANAGE_PRODUCTS in it }, onCategories),
        MenuItem(Icons.Filled.Storefront, R.string.more_store_settings, R.string.more_store_settings_hint, { Permission.MANAGE_SETTINGS in it }, onStoreSettings),
        MenuItem(Icons.Filled.Backup, R.string.more_backup, R.string.more_backup_hint, { Permission.BACKUP in it }, onBackup),
        MenuItem(Icons.Filled.SwapVert, R.string.more_data, R.string.more_data_hint, { Permission.EXPORT_DATA in it || Permission.IMPORT_DATA in it }, onData),
        MenuItem(Icons.Filled.Notifications, R.string.more_prefs, R.string.more_prefs_hint, { Permission.MANAGE_SETTINGS in it }, onPrefs),
        MenuItem(Icons.Filled.Badge, R.string.more_staff, R.string.more_staff_hint, { Permission.MANAGE_STAFF in it }, onStaff),
        MenuItem(Icons.Filled.History, R.string.more_audit, R.string.more_audit_hint, { Permission.VIEW_AUDIT in it }, onAudit),
        MenuItem(Icons.Filled.Cloud, R.string.more_cloud_account, R.string.more_cloud_account_hint, { true }, onCloudAccount),
        MenuItem(
            Icons.Filled.Star,
            R.string.more_rate_app,
            R.string.more_rate_app_hint,
            { true },
        ) {
            openPlayStore(
                context,
                "market://details?id=${context.packageName}",
                "https://play.google.com/store/apps/details?id=${context.packageName}",
            )
        },
        MenuItem(
            Icons.Filled.SystemUpdate,
            R.string.more_check_updates,
            R.string.more_check_updates_hint,
            { true },
        ) {
            openPlayStore(
                context,
                "market://details?id=${context.packageName}",
                "https://play.google.com/store/apps/details?id=${context.packageName}",
            )
        },
        MenuItem(
            Icons.Filled.BugReport,
            R.string.more_report_problem,
            R.string.more_report_problem_hint,
            { true },
        ) {
            val emailIntent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:$SUPPORT_EMAIL?subject=${Uri.encode("StoreVia problem report")}"),
            )
            try {
                context.startActivity(emailIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.more_email_unavailable, Toast.LENGTH_LONG).show()
            }
        },
        MenuItem(
            Icons.Filled.Share,
            R.string.more_share_app,
            R.string.more_share_app_hint,
            { true },
        ) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(
                        R.string.more_share_app_text,
                        "https://play.google.com/store/apps/details?id=${context.packageName}",
                    ),
                )
            }
            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.more_share_app)))
        },
        MenuItem(
            Icons.Filled.Apps,
            R.string.more_other_apps,
            R.string.more_other_apps_hint,
            { true },
        ) {
            openPlayStore(
                context,
                "market://dev?id=$PLAY_DEVELOPER_ID",
                "https://play.google.com/store/apps/developer?id=$PLAY_DEVELOPER_ID",
            )
        },
    ).filter { it.allowed(actor.permissions) }
    val visibleItems = items.map { item ->
        item to (stringResource(item.title) + " " + stringResource(item.hint))
    }.filter { (_, text) -> query.isBlank() || text.contains(query.trim(), ignoreCase = true) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.more_screen_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.more_screen_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text(stringResource(R.string.more_search_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (staffMode) {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.signed_in_as, actor.name, roleLabel(actor.role))) },
                    trailingContent = { TextButton(onClick = access::switchUser) { Text(stringResource(R.string.switch_user)) } },
                )
            }
        }
        if (visibleItems.isEmpty()) {
            Text(
                stringResource(R.string.more_no_matches),
                Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        visibleItems.forEach { (m, _) ->
            Card(Modifier.fillMaxWidth().clickable(onClick = m.onClick)) {
                ListItem(
                    leadingContent = { Icon(m.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(stringResource(m.title), style = MaterialTheme.typography.titleSmall) },
                    supportingContent = { Text(stringResource(m.hint), style = MaterialTheme.typography.bodySmall) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        if (privacyOptionsRequired) {
            OutlinedButton(onClick = onPrivacyOptions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ads_privacy_options))
            }
        }
    }
}
