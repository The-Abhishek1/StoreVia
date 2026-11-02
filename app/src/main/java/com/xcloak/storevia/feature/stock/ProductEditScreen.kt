package com.xcloak.storevia.feature.stock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.database.entity.UnitEntity

private val GST_RATES = listOf(0, 500, 1200, 1800, 2800)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onSaved: (String) -> Unit,
    onArchived: () -> Unit,
    vm: ProductEditViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val units by vm.units.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    val f = ui.form
    var confirmArchive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is EditEvent.Saved -> onSaved(e.productId)
                EditEvent.Archived -> onArchived()
            }
        }
    }

    val unit = units.firstOrNull { it.id == f.unitId }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(if (ui.isNew) R.string.new_product else R.string.edit_product)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field(f.name, { v -> vm.update { copy(name = v) } }, R.string.field_name, errText(ui.errors["name"]))
            Field(f.sku, { v -> vm.update { copy(sku = v) } }, R.string.field_sku)
            Field(
                f.barcode, { v -> vm.update { copy(barcode = v) } }, R.string.field_barcode, errText(ui.errors["barcode"]),
                trailing = {
                    IconButton(onClick = onScan) { Icon(Icons.Filled.QrCodeScanner, stringResource(R.string.scan_barcode)) }
                },
            )
            Dropdown(
                label = stringResource(R.string.field_unit),
                options = units.map { it.id to "${it.name} (${it.symbol})" },
                selectedKey = f.unitId,
                onSelect = { id -> vm.update { copy(unitId = id) } },
            )
            Dropdown(
                label = stringResource(R.string.field_category),
                options = listOf("" to stringResource(R.string.category_none)) + categories.map { it.id to it.name },
                selectedKey = f.categoryId,
                onSelect = { id -> vm.update { copy(categoryId = id) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Field(f.sellPrice, { v -> vm.update { copy(sellPrice = v) } }, R.string.field_sell_price, errText(ui.errors["sell"]), KeyboardType.Decimal)
                }
                Box(Modifier.weight(1f)) {
                    Field(f.cost, { v -> vm.update { copy(cost = v) } }, R.string.field_cost, errText(ui.errors["cost"]), KeyboardType.Decimal)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    Dropdown(
                        label = stringResource(R.string.field_gst),
                        options = GST_RATES.map { it.toString() to "${it / 100}%" },
                        selectedKey = f.gstRateBp.toString(),
                        onSelect = { k -> vm.update { copy(gstRateBp = k.toInt()) } },
                    )
                }
                Box(Modifier.weight(1f)) {
                    Field(f.hsn, { v -> vm.update { copy(hsn = v) } }, R.string.field_hsn, keyboard = KeyboardType.Number)
                }
            }
            SwitchRow(stringResource(R.string.field_tax_inclusive), f.taxInclusive) { v -> vm.update { copy(taxInclusive = v) } }
            Field(
                f.minStock, { v -> vm.update { copy(minStock = v) } }, R.string.field_min_stock, errText(ui.errors["min"]), KeyboardType.Decimal,
                suffix = unit?.symbol,
            )
            if (features.supportsExpiryTracking) {
                SwitchRow(stringResource(R.string.field_track_expiry), f.trackExpiry) { v -> vm.update { copy(trackExpiry = v) } }
            }

            if (!ui.isNew) {
                TextButton(onClick = { confirmArchive = true }) {
                    Text(stringResource(R.string.archive_product), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = vm::save, enabled = !ui.saving,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) { Text(stringResource(R.string.save)) }
    }

    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(stringResource(R.string.archive_confirm_title)) },
            text = { Text(stringResource(R.string.archive_confirm_body)) },
            confirmButton = { TextButton(onClick = { confirmArchive = false; vm.archive() }) { Text(stringResource(R.string.archive)) } },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, options: List<Pair<String, String>>, selectedKey: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selectedKey }?.second.orEmpty(),
            onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); open = false })
            }
        }
    }
}

@Composable
private fun errText(e: FieldError?): String? = when {
    e == null -> null
    e.arg != null -> stringResource(e.res, e.arg)
    else -> stringResource(e.res)
}
