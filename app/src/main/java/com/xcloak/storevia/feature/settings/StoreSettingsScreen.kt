package com.xcloak.storevia.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.xcloak.storevia.R
import com.xcloak.storevia.core.util.GstStates
import com.xcloak.storevia.core.ui.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreSettingsScreen(
    onBack: () -> Unit,
    vm: StoreSettingsViewModel = hiltViewModel(),
    branchVm: BranchSettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val branchUi by branchVm.ui.collectAsStateWithLifecycle()
    val f = ui.form
    var editingBranch by remember { mutableStateOf<com.xcloak.storevia.core.database.entity.BranchEntity?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.saved.collect { onBack() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.store_settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        if (!ui.loaded) {
            if (ui.loading) {
                LoadingState(Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(ui.loadError ?: R.string.err_store_unavailable), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                }
            }
            return@Column
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.store_settings_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.section_business), style = MaterialTheme.typography.titleMedium)
                    Field(f.name, { v -> vm.update { copy(name = v) } }, R.string.field_store_name, errText(ui.errors["name"]))
                    Field(f.ownerName, { v -> vm.update { copy(ownerName = v) } }, R.string.field_owner_name_required)
                    Field(f.address, { v -> vm.update { copy(address = v) } }, R.string.field_address)
                    Field(f.city, { v -> vm.update { copy(city = v) } }, R.string.field_city_required)
                    Field(f.phone, { v -> vm.update { copy(phone = v) } }, R.string.field_phone_plain, errText(ui.errors["phone"]), keyboard = KeyboardType.Phone)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.branch_locations), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.branch_shared_catalog_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (branchUi.cloudMode) {
                        Text(stringResource(R.string.cloud_branch_device_selection_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (branchUi.loading) LoadingState(Modifier.fillMaxWidth().height(100.dp))
                    branchUi.loadError?.let { error ->
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = branchVm::refresh) { Text(stringResource(R.string.retry)) }
                    }
                    branchUi.branches.forEach { branch ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(
                                selected = branch.id == branchUi.selectedId,
                                onClick = { branchVm.select(branch.id) },
                            )
                            Text(branch.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editingBranch = branch }) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.branch_rename))
                            }
                        }
                    }
                    editingBranch?.let { branch ->
                        var name by remember(branch.id) { mutableStateOf(branch.name) }
                        var error by remember(branch.id) { mutableStateOf<Int?>(null) }
                        AlertDialog(
                            onDismissRequest = { editingBranch = null },
                            title = { Text(stringResource(R.string.branch_rename)) },
                            text = {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it; error = null },
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.branch_name)) },
                                    isError = error != null,
                                    supportingText = error?.let { res -> { Text(stringResource(res)) } },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    scope.launch {
                                        error = branchVm.rename(branch.id, name)
                                        if (error == null) editingBranch = null
                                    }
                                }) { Text(stringResource(R.string.save)) }
                            },
                            dismissButton = { TextButton(onClick = { editingBranch = null }) { Text(stringResource(R.string.cancel)) } },
                        )
                    }
                    branchUi.error?.let { error ->
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    }
                    if (!branchUi.cloudMode || !branchUi.loading) {
                        OutlinedTextField(
                            value = branchUi.name,
                            onValueChange = branchVm::setName,
                            singleLine = true,
                            label = { Text(stringResource(R.string.branch_name)) },
                            isError = branchUi.error != null,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = branchVm::add,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !branchUi.loading,
                        ) {
                            Text(stringResource(R.string.branch_add))
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.section_gst), style = MaterialTheme.typography.titleMedium)
                    StateDropdown(f.stateCode, onSelect = { code -> vm.update { copy(stateCode = code) } })
                    Field(f.gstin, { v -> vm.update { copy(gstin = v.uppercase()) } }, R.string.field_gstin, errText(ui.errors["gstin"]), capitalize = true)
                    Text(stringResource(R.string.gstin_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.section_billing), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { Field(f.invoicePrefix, { v -> vm.update { copy(invoicePrefix = v) } }, R.string.field_invoice_prefix, errText(ui.errors["prefix"]), capitalize = true) }
                        Box(Modifier.weight(1f)) {                         Field(
                            f.nextInvoiceNo,
                            { v -> vm.update { copy(nextInvoiceNo = v) } },
                            R.string.field_next_invoice,
                            errText(ui.errors["next"]),
                            KeyboardType.Number,
                            readOnly = ui.cloudMode,
                        ) }
                        if (ui.cloudMode) {
                            Text(
                                stringResource(R.string.cloud_invoice_counter_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Field(f.deviceSeries, { v -> vm.update { copy(deviceSeries = v.uppercase()) } }, R.string.field_device_series, errText(ui.errors["deviceSeries"]), capitalize = true)
                    Text(stringResource(R.string.device_series_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(stringResource(R.string.field_allow_negative), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.allow_negative_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = f.allowNegativeStock, onCheckedChange = { v -> vm.update { copy(allowNegativeStock = v) } })
                    }
                }
            }
        }
        Surface(shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
            Column {
                ui.saveError?.let { error ->
                    Text(
                        stringResource(error),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = vm::save,
                    enabled = !ui.loading,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp),
                ) {
                Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
internal fun Field(
    value: String, onChange: (String) -> Unit, label: Int, error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text, capitalize: Boolean = false,
    readOnly: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, readOnly = readOnly, label = { Text(stringResource(label)) },
        isError = error != null, supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StateDropdown(selected: String, onSelect: (String) -> Unit, error: String? = null) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = if (selected.isBlank()) stringResource(R.string.select_state) else "$selected · ${GstStates.name(selected)}",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.field_state)) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GstStates.all.forEach { (code, name) ->
                DropdownMenuItem(text = { Text("$code · $name") }, onClick = { onSelect(code); open = false })
            }
        }
    }
}

@Composable
private fun errText(res: Int?): String? = res?.let { stringResource(it) }
