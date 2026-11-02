package com.xcloak.storevia.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.util.GstStates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreSettingsScreen(onBack: () -> Unit, vm: StoreSettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val f = ui.form
    LaunchedEffect(Unit) { vm.saved.collect { onBack() } }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.store_settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
        if (!ui.loaded) return@Column

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.store_settings_hint), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.section_business), style = MaterialTheme.typography.titleSmall)
            Field(f.name, { v -> vm.update { copy(name = v) } }, R.string.field_store_name, errText(ui.errors["name"]))
            Field(f.address, { v -> vm.update { copy(address = v) } }, R.string.field_address)
            Field(f.phone, { v -> vm.update { copy(phone = v) } }, R.string.field_phone_plain, keyboard = KeyboardType.Phone)

            Text(stringResource(R.string.section_gst), style = MaterialTheme.typography.titleSmall)
            StateDropdown(f.stateCode) { code -> vm.update { copy(stateCode = code) } }
            Field(f.gstin, { v -> vm.update { copy(gstin = v.uppercase()) } }, R.string.field_gstin, errText(ui.errors["gstin"]), capitalize = true)
            Text(stringResource(R.string.gstin_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.section_billing), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { Field(f.invoicePrefix, { v -> vm.update { copy(invoicePrefix = v) } }, R.string.field_invoice_prefix, errText(ui.errors["prefix"]), capitalize = true) }
                Box(Modifier.weight(1f)) { Field(f.nextInvoiceNo, { v -> vm.update { copy(nextInvoiceNo = v) } }, R.string.field_next_invoice, errText(ui.errors["next"]), KeyboardType.Number) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_allow_negative))
                    Text(stringResource(R.string.allow_negative_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = f.allowNegativeStock, onCheckedChange = { v -> vm.update { copy(allowNegativeStock = v) } })
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text(stringResource(R.string.save)) }
    }
}

@Composable
internal fun Field(
    value: String, onChange: (String) -> Unit, label: Int, error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text, capitalize: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(stringResource(label)) },
        isError = error != null, supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StateDropdown(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = "$selected · ${GstStates.name(selected)}", onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.field_state)) },
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
