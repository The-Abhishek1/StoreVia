package com.xcloak.storevia.core.sync

import androidx.room.withTransaction
import com.xcloak.storevia.core.database.StorviaDatabase
import com.xcloak.storevia.core.database.entity.SyncStateEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.OffsetDateTime

/** The server, as seen by the engine. Implemented by Supabase in the app and by a fake in tests. */
interface SyncRemote {
    /** Rows of [table] after (afterTs, afterId) in (server_updated_at, id) order. Each row carries "server_updated_at". */
    suspend fun pull(table: String, afterTs: String?, afterId: String, limit: Int): List<JsonObject>
    suspend fun push(table: String, rows: List<JsonObject>, ignoreDuplicates: Boolean)
}

data class SyncResult(val pushed: Int, val pulled: Int)

/**
 * Two-way sync: send what changed here, then fetch what changed elsewhere.
 * Safe to run any time and to interrupt: everything it does is repeatable.
 */
class SyncEngine(
    private val db: StorviaDatabase,
    private val remote: SyncRemote,
    private val storeId: String,
    private val rebuilder: CacheRebuilder = CacheRebuilder(db),
    private val tables: List<SyncTable> = SyncTables.all,
    /** Re-read this many seconds before the saved position, so a row that committed late on the server is not missed. */
    private val overlapSeconds: Long = 10,
    private val pageSize: Int = 500,
    private val pushBatch: Int = 200,
) {
    private val schemas = HashMap<String, TableSchema>()
    private fun schema(table: String) = schemas.getOrPut(table) { TableSchema.load(db.openHelper.writableDatabase, table) }

    suspend fun sync(): SyncResult {
        val pushed = push()
        val pulled = pull()
        return SyncResult(pushed, pulled)
    }

    // ------------------------------------------------------------------ push

    private class Pending(val id: String, val json: JsonObject)

    private fun selectDirty(t: SyncTable): List<Pending> {
        val s = schema(t.name)
        val out = mutableListOf<Pending>()
        db.openHelper.readableDatabase.query("SELECT * FROM ${t.name} WHERE dirty = 1 ORDER BY rowid LIMIT $pushBatch").use { c ->
            while (c.moveToNext()) {
                val json = RowMapper.toJson(c, s, t.localOnly)
                out += Pending((json["id"] as JsonPrimitive).content, json)
            }
        }
        return out
    }

    private fun currentJson(t: SyncTable, id: String): JsonObject? =
        db.openHelper.readableDatabase.query("SELECT * FROM ${t.name} WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) RowMapper.toJson(c, schema(t.name), t.localOnly) else null
        }

    private suspend fun push(): Int {
        var total = 0
        for (t in tables) {
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                val batch = selectDirty(t)
                if (batch.isEmpty()) break
                remote.push(t.name, batch.map { it.json.withStoreId() }, ignoreDuplicates = t.kind == SyncKind.APPEND_ONLY)
                total += batch.size
                // Clear the flag only for rows that did not change while we were sending them.
                db.withTransaction {
                    val sdb = db.openHelper.writableDatabase
                    batch.forEach { p -> if (currentJson(t, p.id) == p.json) sdb.execSQL("UPDATE ${t.name} SET dirty = 0 WHERE id = ?", arrayOf(p.id)) }
                }
            }
        }
        return total
    }

    private fun JsonObject.withStoreId(): JsonObject = JsonObject(this + ("store_id" to JsonPrimitive(storeId)))

    // ------------------------------------------------------------------ pull

    private suspend fun pull(): Int {
        val state = db.syncStateDao().all().associate { it.tableName to it.cursorTs }
        var total = 0
        for (t in tables) {
            val saved = state[t.name]
            var afterTs: String? = saved?.let { Instant.parse(it).minusSeconds(overlapSeconds).toString() }
            var afterId = ""
            var newest: Instant? = saved?.let(Instant::parse)

            while (true) {
                val page = remote.pull(t.name, afterTs, afterId, pageSize)
                if (page.isEmpty()) break
                apply(t, page)
                total += page.size
                val last = page.last()
                afterTs = last.str("server_updated_at"); afterId = last.str("id")
                page.forEach { r -> val ts = parse(r.str("server_updated_at")); if (newest == null || ts.isAfter(newest)) newest = ts }
                if (page.size < pageSize) break
            }
            if (newest != null && newest.toString() != saved) db.syncStateDao().upsert(SyncStateEntity(t.name, newest.toString()))
        }
        if (total > 0) rebuilder.recomputeAll()
        return total
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: error("server row without $key")
    private fun parse(ts: String): Instant = OffsetDateTime.parse(ts).toInstant()

    /** One page, one transaction. Foreign keys are checked at the end so order inside a page doesn't matter. */
    private suspend fun apply(t: SyncTable, rows: List<JsonObject>) = db.withTransaction {
        val sdb = db.openHelper.writableDatabase
        sdb.execSQL("PRAGMA defer_foreign_keys = ON")
        val s = schema(t.name)
        for (row in rows) {
            val id = row.str("id")
            val localDirty: Int? = sdb.query("SELECT dirty FROM ${t.name} WHERE id = ?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            val values = RowMapper.toValues(row, s, t.localOnly).apply { put("dirty", 0) }
            when {
                localDirty == null -> {
                    t.localDefaults.forEach { (k, v) -> if (v is Long) values.put(k, v) else values.put(k, v.toString()) }
                    // A clash (say, two phones issued the same invoice number) must stop the sync loudly, never drop a record quietly.
                    sdb.insert(t.name, android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, values)
                }
                t.kind == SyncKind.APPEND_ONLY -> Unit                    // already have it, and it never changes
                localDirty == 0 -> sdb.update(t.name, android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, values, "id = ?", arrayOf(id))
                else -> Unit                                              // unsent local edit wins until it is pushed
            }
        }
    }

    // ------------------------------------------------------------------ switching shops

    /**
     * Throws away everything synced on this phone (not settings) so the cloud copy can be downloaded cleanly.
     * The caller must have made a backup first.
     */
    suspend fun resetLocalData() = db.withTransaction {
        val sdb = db.openHelper.writableDatabase
        sdb.execSQL("DELETE FROM held_carts")
        tables.asReversed().forEach { sdb.execSQL("DELETE FROM ${it.name}") }
        db.syncStateDao().clear()
    }

    private companion object { const val MAX_ROUNDS = 1_000 }
}
