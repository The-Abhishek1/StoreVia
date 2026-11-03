package com.xcloak.storevia.feature.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.money.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformAdminScreen(onBack: () -> Unit, vm: PlatformAdminViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val overview = ui.overview
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AdminStore?>(null) }
    var paying by remember { mutableStateOf<AdminStore?>(null) }
    var deleting by remember { mutableStateOf<AdminStore?>(null) }

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
            if (ui.accessDenied) {
                item { Text(ui.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            } else if (overview != null) {
                item {
                    Text(stringResource(R.string.platform_admin_overview), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        InsightCard(stringResource(R.string.platform_admin_total_stores), overview.insights.total.toString(), Modifier.weight(1f))
                        InsightCard(stringResource(R.string.platform_admin_active_stores), overview.insights.active.toString(), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        InsightCard(stringResource(R.string.platform_admin_held_stores), overview.insights.held.toString(), Modifier.weight(1f))
                        InsightCard(stringResource(R.string.platform_admin_pro_stores), overview.insights.pro.toString(), Modifier.weight(1f))
                    }
                }
                item {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.platform_admin_store_list)) })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.platform_admin_payments)) })
                    }
                }
                if (selectedTab == 0) {
                    item {
                        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.platform_admin_add_store), Modifier.padding(start = 8.dp))
                        }
                    }
                    if (overview.stores.isEmpty()) item { Text(stringResource(R.string.platform_admin_no_stores)) }
                    items(overview.stores, key = { it.storeId }) { store ->
                        AdminStoreRow(
                            store = store,
                            onEdit = { editing = store },
                            onPay = { paying = store },
                            onHold = { if (store.status == "held") vm.restore(store.storeId) else vm.hold(store.storeId) },
                            onDelete = { deleting = store },
                        )
                    }
                } else {
                    item {
                        InsightCard(
                            stringResource(R.string.platform_admin_recorded_payments),
                            Money(overview.insights.recordedAmountMinor).format(),
                            Modifier.fillMaxWidth(),
                        )
                    }
                    if (overview.payments.isEmpty()) item { Text(stringResource(R.string.platform_admin_no_payments)) }
                    items(overview.payments, key = { it.id }) { payment ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    payment.amountMinor?.let { Money(it).format() } ?: stringResource(R.string.platform_admin_verified_purchase),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("${payment.provider} · ${payment.status} · ${payment.storeId}", style = MaterialTheme.typography.bodySmall)
                                payment.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                Text(payment.createdAt, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            ui.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        }
    }

    if (creating) StoreEditorDialog(onDismiss = { creating = false }, onSave = { name, email, type, _, _, _ -> vm.createStore(name, email, type); creating = false })
    editing?.let { store -> StoreEditorDialog(store, onDismiss = { editing = null }, onSave = { name, _, type, address, phone, gstin -> vm.updateStore(store, name, address, phone, gstin, type); editing = null }) }
    paying?.let { store -> PaymentDialog(storeName = store.store?.name ?: store.storeId, onDismiss = { paying = null }, onSave = { amount, note -> vm.recordPayment(store.storeId, amount, note); paying = null }) }
    deleting?.let { store ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.platform_admin_delete)) },
            text = { Text(stringResource(R.string.platform_admin_delete_confirm, store.store?.name ?: store.storeId)) },
            confirmButton = { TextButton(onClick = { vm.delete(store.storeId); deleting = null }) { Text(stringResource(R.string.platform_admin_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun InsightCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AdminStoreRow(store: AdminStore, onEdit: () -> Unit, onPay: () -> Unit, onHold: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(store.store?.name ?: stringResource(R.string.platform_admin_store_unclaimed), style = MaterialTheme.typography.titleMedium)
            Text("${store.storeType} · ${store.plan.uppercase()} · ${store.status}", style = MaterialTheme.typography.bodySmall)
            store.store?.phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Edit, contentDescription = null); Text(stringResource(R.string.platform_admin_edit)) }
                    TextButton(onClick = onPay, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Payments, contentDescription = null); Text(stringResource(R.string.platform_admin_record_payment)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onHold, modifier = Modifier.weight(1f)) {
                        Text(stringResource(if (store.status == "held" || store.status == "deleted") R.string.platform_admin_restore else R.string.platform_admin_hold))
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.platform_admin_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreEditorDialog(
    store: AdminStore? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, ownerEmail: String, type: String, address: String, phone: String, gstin: String) -> Unit,
) {
    var name by remember(store) { mutableStateOf(store?.store?.name.orEmpty()) }
    var ownerEmail by remember(store) { mutableStateOf("") }
    var type by remember(store) { mutableStateOf(store?.storeType ?: "GENERAL") }
    var address by remember(store) { mutableStateOf(store?.store?.address.orEmpty()) }
    var phone by remember(store) { mutableStateOf(store?.store?.phone.orEmpty()) }
    var gstin by remember(store) { mutableStateOf(store?.store?.gstin.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (store == null) R.string.platform_admin_add_store else R.string.platform_admin_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.field_store_name)) })
                if (store == null) OutlinedTextField(ownerEmail, { ownerEmail = it }, singleLine = true, label = { Text(stringResource(R.string.platform_admin_owner_email)) })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("GENERAL", "MEDICAL", "WHOLESALER").forEach { value -> FilterChip(type == value, { type = value }, label = { Text(value) }) }
                }
                if (store != null) {
                    OutlinedTextField(address, { address = it }, singleLine = true, label = { Text(stringResource(R.string.field_address)) })
                    OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text(stringResource(R.string.field_phone)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    OutlinedTextField(gstin, { gstin = it }, singleLine = true, label = { Text(stringResource(R.string.field_gstin)) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, ownerEmail, type, address, phone, gstin) }, enabled = name.isNotBlank() && (store != null || ownerEmail.contains('@'))) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PaymentDialog(storeName: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.platform_admin_record_payment)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(storeName, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(amount, { amount = it }, singleLine = true, label = { Text(stringResource(R.string.platform_admin_amount_inr)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.platform_admin_payment_note)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(amount, note) }, enabled = amount.isNotBlank() && note.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
