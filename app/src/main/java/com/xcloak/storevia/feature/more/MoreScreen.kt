package com.xcloak.storevia.feature.more

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

/** Everything that is not a bottom-bar tab. An entry only appears if the signed-in person may use it. */
@Composable
fun MoreScreen(
    onCustomers: () -> Unit, onStoreSettings: () -> Unit, onBackup: () -> Unit, onData: () -> Unit, onPrefs: () -> Unit,
    onCategories: () -> Unit, onSuppliers: () -> Unit, onPurchases: () -> Unit, onReorder: () -> Unit,
    onStaff: () -> Unit, onAudit: () -> Unit, onCloudAccount: () -> Unit,
    access: AccessViewModel = hiltViewModel(),
) {
    val actor = LocalActor.current
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
    }
}
