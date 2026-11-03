package com.xcloak.storevia.feature.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.data.export.ExportKind
import com.xcloak.storevia.domain.imports.DuplicatePolicy
import com.xcloak.storevia.domain.imports.ImportError
import com.xcloak.storevia.domain.imports.RowError

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportExportScreen(onBack: () -> Unit, vm: ImportExportViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pendingExport by remember { mutableStateOf<ExportKind?>(null) }
    val canImport = can(Permission.IMPORT_DATA)
    val canExport = can(Permission.EXPORT_DATA)
    val proEnabled = ui.proAccessChecked && ui.proAccessEnabled

    LaunchedEffect(Unit) { vm.refreshProAccess() }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val kind = pendingExport
        if (uri != null && kind != null) vm.export(kind, uri)
        pendingExport = null
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.readImport(uri) }

    val snackbar = remember { SnackbarHostState() }
    val message = ui.message?.let { stringResource(it, *ui.messageArgs.toTypedArray()) }
    LaunchedEffect(message) { if (message != null) { snackbar.showSnackbar(message); vm.dismissMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Text(stringResource(R.string.section_import), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodyMedium)
            if (!proEnabled) Text(
                ui.proAccessError ?: stringResource(
                    if (ui.proAccessChecked) R.string.data_pro_required else R.string.data_pro_checking,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.proAccessError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pendingExport = ExportKind.TEMPLATE; createLauncher.launch(ExportKind.TEMPLATE.fileName) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.download_template)) }
                Button(onClick = { openLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }, enabled = !ui.busy && canImport && proEnabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.choose_csv))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.section_export), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ExportKind.PRODUCTS to R.string.export_products, ExportKind.STOCK to R.string.export_stock,
                    ExportKind.SALES to R.string.export_sales, ExportKind.CUSTOMERS to R.string.export_customers,
                ).forEach { (kind, label) ->
                    OutlinedButton(onClick = { pendingExport = kind; createLauncher.launch(kind.fileName) }, enabled = !ui.busy && canExport && proEnabled) { Text(stringResource(label)) }
                }
            }
        }
    }

    ui.pending?.let { p ->
        val parse = p.parse
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text(stringResource(R.string.import_preview_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parse.missingColumns.isNotEmpty()) {
                        Text(stringResource(R.string.import_missing_columns, parse.missingColumns.joinToString(", ")), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.import_counts, p.preview.newRows, p.preview.existingRows), style = MaterialTheme.typography.titleSmall)
                        if (parse.errors.isNotEmpty()) {
                            Text(stringResource(R.string.import_problems, parse.errors.map { it.line }.distinct().size), color = MaterialTheme.colorScheme.error)
                            parse.errors.take(6).forEach { Text(errorText(it), style = MaterialTheme.typography.bodySmall) }
                            if (parse.errors.size > 6) Text(stringResource(R.string.import_more_problems, parse.errors.size - 6), style = MaterialTheme.typography.bodySmall)
                        }
                        if (p.preview.existingRows > 0) {
                            Text(stringResource(R.string.import_existing_policy), style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(p.policy == DuplicatePolicy.SKIP, { vm.setPolicy(DuplicatePolicy.SKIP) }, { Text(stringResource(R.string.policy_skip)) })
                                FilterChip(p.policy == DuplicatePolicy.UPDATE, { vm.setPolicy(DuplicatePolicy.UPDATE) }, { Text(stringResource(R.string.policy_update)) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmImport, enabled = parse.rows.isNotEmpty()) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun errorText(e: RowError): String {
    val res = when (e.error) {
        ImportError.NAME_REQUIRED -> R.string.imp_name_required
        ImportError.PRICE_INVALID -> R.string.imp_price_invalid
        ImportError.COST_INVALID -> R.string.imp_cost_invalid
        ImportError.MRP_INVALID -> R.string.imp_mrp_invalid
        ImportError.GST_INVALID -> R.string.imp_gst_invalid
        ImportError.TAX_FLAG_INVALID -> R.string.imp_tax_flag_invalid
        ImportError.QTY_INVALID -> R.string.imp_qty_invalid
        ImportError.MIN_STOCK_INVALID -> R.string.imp_min_invalid
        ImportError.EXPIRY_INVALID -> R.string.imp_expiry_invalid
        ImportError.UNIT_UNKNOWN -> R.string.imp_unit_unknown
        ImportError.DUPLICATE_IN_FILE -> R.string.imp_duplicate
    }
    return stringResource(res, e.line, e.detail)
}
