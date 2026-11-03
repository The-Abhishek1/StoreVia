package com.xcloak.storevia.feature.data

import com.xcloak.storevia.core.access.*
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xcloak.storevia.R
import com.xcloak.storevia.data.backup.AutoBackup
import com.xcloak.storevia.data.backup.BackupManager
import com.xcloak.storevia.data.backup.RestoreCheck
import com.xcloak.storevia.data.cloud.CloudAccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject

data class BackupUi(
    val lastManualAt: Long = 0,
    val autoBackups: List<AutoBackup> = emptyList(),
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
    val askPassphrase: Boolean = false,
    val passphraseWrong: Boolean = false,
    /** A checked backup waiting for the user's final OK. */
    val confirmRestore: File? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupManager,
    private val access: Access,
    private val audit: AuditSink,
    private val prefs: com.xcloak.storevia.data.prefs.AppPrefs,
    private val cloud: CloudAccountRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    /** Raw bytes of the file being restored, kept only while waiting for a passphrase. */
    private var pendingBytes: ByteArray? = null

    init { refresh() }

    private fun refresh() = _ui.update { it.copy(lastManualAt = backups.lastManualBackupAt, autoBackups = backups.autoBackups()) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun backup(uri: Uri, passphrase: String?) = viewModelScope.launch {
        if (!access.actor().can(Permission.BACKUP)) { _ui.update { it.copy(message = R.string.err_permission_denied) }; return@launch }
        _ui.update { it.copy(busy = true) }
        val ok = try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { backups.writeBackup(it, passphrase) } != null
            }
        } catch (_: Exception) { false }
        if (ok) audit.log(AuditAction.BACKUP, "Backup saved" + if (passphrase.isNullOrEmpty()) "" else " (passphrase protected)")
        _ui.update { it.copy(busy = false, message = if (ok) R.string.backup_done else R.string.backup_failed) }
        refresh()
    }

    fun backupToCloud(passphrase: String?) = viewModelScope.launch {
        if (!access.actor().can(Permission.BACKUP)) { _ui.update { it.copy(message = R.string.err_permission_denied) }; return@launch }
        if (passphrase.isNullOrBlank()) { _ui.update { it.copy(message = R.string.cloud_backup_passphrase_required) }; return@launch }
        _ui.update { it.copy(busy = true) }
        val result = runCatching { cloud.uploadBackup(passphrase) }
        if (result.isSuccess) audit.log(AuditAction.BACKUP, "Encrypted cloud backup saved")
        _ui.update { it.copy(busy = false, message = if (result.isSuccess) R.string.cloud_backup_done else R.string.cloud_backup_failed) }
        refresh()
    }

    fun restoreCloudBackup() = viewModelScope.launch {
        if (!access.actor().can(Permission.RESTORE)) { _ui.update { it.copy(message = R.string.err_permission_denied) }; return@launch }
        _ui.update { it.copy(busy = true) }
        val bytes = runCatching { cloud.downloadBackup() }.getOrNull()
        if (bytes == null) { _ui.update { it.copy(busy = false, message = R.string.cloud_backup_restore_failed) }; return@launch }
        check(bytes, null)
    }

    fun startRestore(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(busy = true) }
        val bytes = try {
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readCapped(it) } }
        } catch (_: Exception) { null }
        if (bytes == null) { _ui.update { it.copy(busy = false, message = R.string.restore_read_failed) }; return@launch }
        check(bytes, null)
    }

    fun startRestore(file: File) = viewModelScope.launch {
        _ui.update { it.copy(busy = true) }
        val bytes = try { withContext(Dispatchers.IO) { file.readBytes() } } catch (_: Exception) { null }
        if (bytes == null) { _ui.update { it.copy(busy = false, message = R.string.restore_read_failed) }; return@launch }
        check(bytes, null)
    }

    fun submitPassphrase(passphrase: String) {
        val bytes = pendingBytes ?: return
        viewModelScope.launch { check(bytes, passphrase) }
    }

    fun cancelRestore() {
        pendingBytes = null
        _ui.value.confirmRestore?.delete()
        _ui.update { it.copy(askPassphrase = false, passphraseWrong = false, confirmRestore = null, busy = false) }
    }

    fun confirmRestore() {
        val staged = _ui.value.confirmRestore ?: return
        if (!access.actor().can(Permission.RESTORE)) { cancelRestore(); _ui.update { it.copy(message = R.string.err_permission_denied) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, confirmRestore = null) }
            val a = access.actor()
            prefs.setPendingAudit(AuditAction.RESTORE.name, "Database restored from a backup", a.name.ifEmpty { "?" }, a.role.name)
            backups.applyRestore(staged)
            backups.restartApp()
        }
    }

    private suspend fun check(bytes: ByteArray, passphrase: String?) {
        when (val r = backups.prepareRestore(bytes, passphrase)) {
            is RestoreCheck.Ok -> { pendingBytes = null; _ui.update { it.copy(busy = false, askPassphrase = false, passphraseWrong = false, confirmRestore = r.staged) } }
            RestoreCheck.NeedsPassphrase -> { pendingBytes = bytes; _ui.update { it.copy(busy = false, askPassphrase = true, passphraseWrong = false) } }
            RestoreCheck.WrongPassphrase -> { pendingBytes = bytes; _ui.update { it.copy(busy = false, askPassphrase = true, passphraseWrong = true) } }
            RestoreCheck.NotABackup -> fail(R.string.restore_not_backup)
            RestoreCheck.TooNew -> fail(R.string.restore_too_new)
            RestoreCheck.Corrupt -> fail(R.string.restore_corrupt)
        }
    }

    private fun fail(@StringRes res: Int) { pendingBytes = null; _ui.update { it.copy(busy = false, message = res, askPassphrase = false) } }

    /** Backups are small; refuse anything absurd rather than run out of memory. */
    private fun readCapped(input: InputStream, limit: Int = 200 * 1024 * 1024): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
