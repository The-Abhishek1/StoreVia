package com.xcloak.storevia.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import com.xcloak.storevia.core.database.StorviaDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class AutoBackup(val file: File, val createdAt: Long, val sizeBytes: Long)

sealed interface RestoreCheck {
    /** The backup is valid; [staged] is a checked copy of its database, ready to be swapped in. */
    data class Ok(val staged: File) : RestoreCheck
    data object NeedsPassphrase : RestoreCheck
    data object WrongPassphrase : RestoreCheck
    data object NotABackup : RestoreCheck
    data object TooNew : RestoreCheck
    data object Corrupt : RestoreCheck
}

/**
 * Backup file = ZIP { manifest.json, storevia.db }, optionally wrapped by [BackupCrypto].
 * Manual backups go wherever the user picks (Drive, SD card...). Automatic ones stay in app storage
 * (protects against mistakes and corruption, but is deleted on uninstall, so export regularly too).
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: StorviaDatabase,
) {
    private val prefs by lazy { context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE) }
    private val dbFile: File get() = context.getDatabasePath(DB_NAME)
    private val autoDir: File get() = File(context.filesDir, "backups").apply { mkdirs() }

    var lastManualBackupAt: Long
        get() = prefs.getLong("last_manual", 0L)
        private set(v) { prefs.edit().putLong("last_manual", v).apply() }

    // ---------------------------------------------------------------- create

    suspend fun writeBackup(out: OutputStream, passphrase: String?) = withContext(Dispatchers.IO) {
        val zip = snapshotZip()
        out.write(if (passphrase.isNullOrEmpty()) zip else BackupCrypto.encrypt(zip, passphrase))
        out.flush()
        lastManualBackupAt = System.currentTimeMillis()
    }

    suspend fun autoBackup(keep: Int = 7) = withContext(Dispatchers.IO) {
        val name = "auto-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".svbackup"
        File(autoDir, name).writeBytes(snapshotZip())
        autoDir.listFiles { f -> f.name.startsWith("auto-") }?.sortedByDescending { it.name }?.drop(keep)?.forEach { it.delete() }
    }

    fun autoBackups(): List<AutoBackup> =
        autoDir.listFiles { f -> f.name.startsWith("auto-") && f.name.endsWith(".svbackup") }.orEmpty()
            .sortedByDescending { it.name }.map { AutoBackup(it, it.lastModified(), it.length()) }

    /** Consistent copy of the database: flush the WAL into the main file, then zip it with a manifest. */
    private fun snapshotZip(): ByteArray {
        val sdb = db.openHelper.writableDatabase
        sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val manifest = """{"app":"storevia","dbVersion":${sdb.version},"createdAt":${System.currentTimeMillis()}}"""
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry(MANIFEST)); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry(DB_ENTRY)); dbFile.inputStream().use { it.copyTo(z) }; z.closeEntry()
        }
        return bytes.toByteArray()
    }

    // --------------------------------------------------------------- restore

    /** Decrypts (if needed), unzips to a staging file and checks it is a healthy Storevia database. */
    suspend fun prepareRestore(data: ByteArray, passphrase: String?): RestoreCheck = withContext(Dispatchers.IO) {
        var bytes = data
        if (BackupCrypto.isEncrypted(bytes)) {
            if (passphrase.isNullOrEmpty()) return@withContext RestoreCheck.NeedsPassphrase
            bytes = try { BackupCrypto.decrypt(bytes, passphrase) } catch (_: WrongPassphraseException) { return@withContext RestoreCheck.WrongPassphrase }
        }
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return@withContext RestoreCheck.NotABackup

        var manifest: String? = null
        val staged = File(context.cacheDir, "restore-staging.db").also { it.delete() }
        try {
            ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    // Only two known entry names are read, so a crafted ZIP can't write elsewhere (zip-slip).
                    when (e.name) {
                        MANIFEST -> manifest = z.readBytes().toString(Charsets.UTF_8)
                        DB_ENTRY -> staged.outputStream().use { z.copyTo(it) }
                    }
                    e = z.nextEntry
                }
            }
        } catch (_: Exception) { return@withContext RestoreCheck.NotABackup }

        val m = manifest
        if (m == null || !staged.exists() || !m.contains("\"app\":\"storevia\"")) return@withContext RestoreCheck.NotABackup
        val version = Regex("\"dbVersion\":(\\d+)").find(m)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext RestoreCheck.NotABackup
        if (version > db.openHelper.readableDatabase.version) return@withContext RestoreCheck.TooNew

        val healthy = try {
            SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { check ->
                val integrity = check.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
                val hasStore = check.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='store'", null).use { it.moveToFirst() }
                integrity && hasStore
            }
        } catch (_: Exception) { false }
        if (healthy) RestoreCheck.Ok(staged) else RestoreCheck.Corrupt
    }

    /** Swaps the staged database in. The caller must restart the app straight after. */
    suspend fun applyRestore(staged: File) = withContext(Dispatchers.IO) {
        // Keep the data we are about to replace, in case the user regrets it.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        dbFile.copyTo(File(context.filesDir, "pre-restore.db"), overwrite = true)
        db.close()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        staged.copyTo(dbFile, overwrite = true)
        staged.delete()
    }

    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val DB_NAME = "storevia.db"
        private const val MANIFEST = "manifest.json"
        private const val DB_ENTRY = "storevia.db"
    }
}
