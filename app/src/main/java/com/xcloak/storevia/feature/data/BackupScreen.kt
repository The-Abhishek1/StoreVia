package com.xcloak.storevia.feature.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xcloak.storevia.R
import com.xcloak.storevia.core.access.Permission
import com.xcloak.storevia.core.ui.can
import com.xcloak.storevia.data.backup.AutoBackup
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var askBackupPassphrase by remember { mutableStateOf(false) }
    var backupToCloud by remember { mutableStateOf(false) }
    var restoreFromAuto by remember { mutableStateOf<AutoBackup?>(null) }
    val canRestore = can(Permission.RESTORE)

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.backup(uri) else vm.cancelBackup()
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.startRestore(uri) }
    val fileName = "storevia-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".svbackup"
    val snackbar = remember { SnackbarHostState() }
    val message = ui.message?.let { stringResource(it) }
    LaunchedEffect(message) { if (message != null) { snackbar.showSnackbar(message); vm.dismissMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                Text(stringResource(R.string.last_backup), style = MaterialTheme.typography.labelLarge)
                Text(
                    if (ui.lastManualAt == 0L) stringResource(R.string.never_backed_up) else dateFmt.format(Date(ui.lastManualAt)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ui.lastManualAt == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            item {
                Button(onClick = { backupToCloud = false; askBackupPassphrase = true }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.backup_now))
                }
                Text(stringResource(R.string.backup_now_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                OutlinedButton(
                    onClick = { backupToCloud = true; askBackupPassphrase = true },
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                ) { Text(stringResource(R.string.cloud_backup_now)) }
            }
            item {
                if (canRestore) OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.restore_from_file))
                }
                if (canRestore) OutlinedButton(onClick = vm::restoreCloudBackup, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
                    Text(stringResource(R.string.cloud_restore_backup))
                }
            }
            if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.auto_backups_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.auto_backups_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (ui.autoBackups.isEmpty()) item { Text(stringResource(R.string.auto_backups_none), style = MaterialTheme.typography.bodyMedium) }
            else items(ui.autoBackups, key = { it.file.name }) { b ->
                ListItem(
                    modifier = Modifier.clickable(enabled = !ui.busy && canRestore) { restoreFromAuto = b },
                    headlineContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(b.createdAt))) },
                    supportingContent = { Text("${b.sizeBytes / 1024} KB") },
                    trailingContent = { if (canRestore) TextButton(onClick = { restoreFromAuto = b }) { Text(stringResource(R.string.restore_action)) } },
                )
                HorizontalDivider()
            }
        }
    }

    if (askBackupPassphrase) {
        PassphraseSetDialog(
            onDismiss = { askBackupPassphrase = false; backupToCloud = false },
            onConfirm = { pass ->
                askBackupPassphrase = false
                if (backupToCloud) {
                    backupToCloud = false
                    vm.backupToCloud(pass)
                } else {
                    vm.prepareBackup(pass)
                    createLauncher.launch(fileName)
                }
            },
        )
    }
    restoreFromAuto?.let { b ->
        AlertDialog(
            onDismissRequest = { restoreFromAuto = null },
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_body)) },
            confirmButton = { TextButton(onClick = { restoreFromAuto = null; vm.startRestore(b.file) }) { Text(stringResource(R.string.restore_action)) } },
            dismissButton = { TextButton(onClick = { restoreFromAuto = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (ui.askPassphrase) {
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text(stringResource(R.string.restore_passphrase_title)) },
            text = {
                OutlinedTextField(pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.field_passphrase)) }, isError = ui.passphraseWrong,
                    supportingText = if (ui.passphraseWrong) { { Text(stringResource(R.string.restore_passphrase_wrong)) } } else null)
            },
            confirmButton = { TextButton(onClick = { vm.submitPassphrase(pass) }, enabled = pass.isNotEmpty()) { Text(stringResource(R.string.continue_label)) } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (ui.confirmRestore != null) {
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_body)) },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text(stringResource(R.string.restore_action), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Optional passphrase. Empty = no encryption. [onConfirm] gets null or the passphrase. */
@Composable
private fun PassphraseSetDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = pass != again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_passphrase_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pass, { pass = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.field_passphrase)) })
                OutlinedTextField(again, { again = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.field_passphrase_confirm)) }, isError = mismatch && again.isNotEmpty(),
                    supportingText = if (mismatch && again.isNotEmpty()) { { Text(stringResource(R.string.passphrase_mismatch)) } } else null)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pass.ifEmpty { null }) }, enabled = !mismatch) { Text(stringResource(R.string.continue_label)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
