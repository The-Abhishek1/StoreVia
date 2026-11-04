package com.xcloak.storevia.domain

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.StoreEntity
import com.xcloak.storevia.data.backup.BackupManager
import com.xcloak.storevia.data.backup.RestoreCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Uses a real file-backed database (backups copy the database file), named like the app's. */
@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var ctx: Context
    private lateinit var db: StorviaDatabase
    private lateinit var manager: BackupManager

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(BackupManager.DB_NAME)
        db = Room.databaseBuilder(ctx, StorviaDatabase::class.java, BackupManager.DB_NAME).allowMainThreadQueries().build()
        manager = BackupManager(ctx, db)
    }

    @After fun tearDown() {
        db.close()
        File(ctx.cacheDir, "restore-staging.db").delete()
        ctx.deleteDatabase(BackupManager.DB_NAME)
    }

    private fun storeNameIn(path: String): String =
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { c ->
            c.rawQuery("SELECT name FROM store", null).use { it.moveToFirst(); it.getString(0) }
        }

    @Test fun plainBackupRestoresToAHealthyDatabase() = runBlocking {
        db.storeDao().upsert(StoreEntity(name = "Ravi Stores"))
        val out = ByteArrayOutputStream(); manager.writeBackup(out, null)
        db.storeDao().upsert(db.storeDao().get()!!.copy(name = "Changed after backup"))

        val ok = manager.prepareRestore(out.toByteArray(), null) as RestoreCheck.Ok
        assertEquals("Ravi Stores", storeNameIn(ok.staged.path))      // the snapshot, not the later change
        assertTrue(manager.lastManualBackupAt > 0)
    }

    @Test fun passphraseBackupNeedsTheRightPassphrase() = runBlocking {
        db.storeDao().upsert(StoreEntity(name = "Ravi Stores"))
        val out = ByteArrayOutputStream(); manager.writeBackup(out, "s3cret")
        val bytes = out.toByteArray()
        assertEquals(RestoreCheck.NeedsPassphrase, manager.prepareRestore(bytes, null))
        assertEquals(RestoreCheck.WrongPassphrase, manager.prepareRestore(bytes, "nope"))
        val ok = manager.prepareRestore(bytes, "s3cret") as RestoreCheck.Ok
        assertEquals("Ravi Stores", storeNameIn(ok.staged.path))
    }

    @Test fun randomFilesAreRejected() = runBlocking {
        assertEquals(RestoreCheck.NotABackup, manager.prepareRestore(ByteArray(200) { 7 }, null))
        assertEquals(RestoreCheck.NotABackup, manager.prepareRestore(ByteArray(2), null))
    }

    @Test fun restoreRejectsOversizedManifestAndUnknownEntries() = runBlocking {
        val oversizedManifest = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(ByteArray(64 * 1024 + 1))
                zip.closeEntry()
            }
        }.toByteArray()
        assertEquals(RestoreCheck.Corrupt, manager.prepareRestore(oversizedManifest, null))
        assertFalse(File(ctx.cacheDir, "restore-staging.db").exists())

        val unknownEntry = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("unexpected.bin"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }.toByteArray()
        assertEquals(RestoreCheck.NotABackup, manager.prepareRestore(unknownEntry, null))
        assertFalse(File(ctx.cacheDir, "restore-staging.db").exists())
    }

    @Test fun restoreRejectsManifestDatabaseVersionMismatch() = runBlocking {
        db.storeDao().upsert(StoreEntity(name = "Ravi Stores"))
        val backupOutput = ByteArrayOutputStream()
        manager.writeBackup(backupOutput, null)
        val original = backupOutput.toByteArray()
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(original.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val actualVersion = db.openHelper.writableDatabase.version
        val forgedManifest = entries.getValue("manifest.json")
            .toString(Charsets.UTF_8)
            .replace(Regex("\"dbVersion\":\\d+"), "\"dbVersion\":${actualVersion - 1}")
        entries["manifest.json"] = forgedManifest.toByteArray()
        val forgedBackup = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        assertEquals(RestoreCheck.Corrupt, manager.prepareRestore(forgedBackup, null))
        assertFalse(File(ctx.cacheDir, "restore-staging.db").exists())
    }

    @Test fun autoBackupsAreCreatedAndPruned() = runBlocking {
        db.storeDao().upsert(StoreEntity(name = "Ravi Stores"))
        repeat(3) { manager.autoBackup(keep = 2); Thread.sleep(1100) }   // file names are per-second
        assertEquals(2, manager.autoBackups().size)
        manager.autoBackups().forEach { it.file.delete() }
    }
}
