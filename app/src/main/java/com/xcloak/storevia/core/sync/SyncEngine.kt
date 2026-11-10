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
    private fun hasDirtyColumn(t: SyncTable) = schema(t.name).columns.any { it.name == "dirty" }

    init {
        val order = tables.mapIndexed { index, table -> table.name to index }.toMap()
        tables.forEach { table ->
            table.dependsOn.forEach { dependency ->
                val dependencyIndex = order[dependency]
                val tableIndex = order[table.name]
                require(dependencyIndex == null || dependencyIndex < tableIndex!!) {
                    "Sync table '${table.name}' must follow its dependency '$dependency'."
                }
            }
        }
    }

    suspend fun sync(): SyncResult {
        val pushed = push()
        val pulled = pull().values.sumOf { it.size }
        return SyncResult(pushed, pulled)
    }

    /** Refreshes the local read cache from the cloud without ever uploading Room changes. */
    suspend fun pullOnly(): SyncResult {
        val pulled = pull().values.sumOf { it.size }
        return SyncResult(pushed = 0, pulled = pulled)
    }

    /**
     * Uploads every local synced row, clears old cursors, downloads the full cloud snapshot, and
     * checks that every table converged before a caller enables cloud mode.
     */
    suspend fun transferLocalDatasetAndVerify(): SyncResult {
        val expectedByTable = tables.associate { it.name to localSnapshot(it) }
        db.withTransaction {
            val sdb = db.openHelper.writableDatabase
            tables.filter(::hasDirtyColumn).forEach { sdb.execSQL("UPDATE ${it.name} SET dirty = 1") }
            db.syncStateDao().clear()
        }

        val pushed = push()
        val pulledByTable = pull()
        db.withTransaction {
            tables.forEach { table ->
                val local = localSnapshot(table)
                val cloud = pulledByTable[table.name].orEmpty()
                    .mapValues { (_, row) -> withoutServerMetadata(row, table.localOnly) }
                val expected = expectedByTable.getValue(table.name)
                val pendingCount = if (hasDirtyColumn(table)) {
                    db.openHelper.readableDatabase.query(
                        "SELECT COUNT(*) FROM ${table.name} WHERE dirty = 1",
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    }
                } else 0L
                val changedLocally = differingFields(expected, local)
                val cloudMismatch = differingFields(local, cloud)
                check(pendingCount == 0L && changedLocally.isEmpty() && cloudMismatch.isEmpty()) {
                    "Cloud verification failed for '${table.name}' " +
                        "(local=${local.size}, cloud=${cloud.size}, pending=$pendingCount, " +
                        "local_changed=${changedLocally.sorted().joinToString().ifEmpty { "none" }}, " +
                        "cloud_fields=${cloudMismatch.sorted().joinToString().ifEmpty { "none" }})."
                }
            }
        }
        return SyncResult(pushed, pulledByTable.values.sumOf { it.size })
    }

    // ------------------------------------------------------------------ push

    private class Pending(val id: String, val json: JsonObject)

    private fun selectDirty(t: SyncTable): List<Pending> {
        val s = schema(t.name)
        val out = mutableListOf<Pending>()
        val dirtyFilter = if (s.columns.any { it.name == "dirty" }) " WHERE dirty = 1" else ""
        db.openHelper.readableDatabase.query("SELECT * FROM ${t.name}$dirtyFilter ORDER BY rowid LIMIT $pushBatch").use { c ->
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
                if (hasDirtyColumn(t)) db.withTransaction {
                    val sdb = db.openHelper.writableDatabase
                    batch.forEach { p -> if (currentJson(t, p.id) == p.json) sdb.execSQL("UPDATE ${t.name} SET dirty = 0 WHERE id = ?", arrayOf(p.id)) }
                }
            }
        }
        return total
    }

    private fun JsonObject.withStoreId(): JsonObject = JsonObject(this + ("store_id" to JsonPrimitive(storeId)))

    // ------------------------------------------------------------------ pull

    private suspend fun pull(): Map<String, Map<String, JsonObject>> {
        val state = db.syncStateDao().all().associate { it.tableName to it.cursorTs }
        val pulledByTable = linkedMapOf<String, Map<String, JsonObject>>()
        for (t in tables) {
            val saved = state[t.name]
            var afterTs: String? = saved?.let { Instant.parse(it).minusSeconds(overlapSeconds).toString() }
            var afterId = ""
            var newest: Instant? = saved?.let(Instant::parse)
            val pulledRows = linkedMapOf<String, JsonObject>()

            while (true) {
                val page = remote.pull(t.name, afterTs, afterId, pageSize)
                if (page.isEmpty()) break
                apply(t, page)
                page.forEach { pulledRows[it.str("id")] = it }
                val last = page.last()
                afterTs = last.str("server_updated_at"); afterId = last.str("id")
                page.forEach { r -> val ts = parse(r.str("server_updated_at")); if (newest == null || ts.isAfter(newest)) newest = ts }
                if (page.size < pageSize) break
            }
            pulledByTable[t.name] = pulledRows
            if (newest != null && newest.toString() != saved) db.syncStateDao().upsert(SyncStateEntity(t.name, newest.toString()))
        }
        if (pulledByTable.values.sumOf { it.size } > 0) rebuilder.recomputeAll()
        return pulledByTable
    }

    private fun localSnapshot(t: SyncTable): Map<String, JsonObject> {
        val schema = schema(t.name)
        val rows = linkedMapOf<String, JsonObject>()
        db.openHelper.readableDatabase.query("SELECT * FROM ${t.name}").use { cursor ->
            while (cursor.moveToNext()) {
                val json = RowMapper.toJson(cursor, schema, t.localOnly)
                rows[json.str("id")] = json
            }
        }
        return rows
    }

    private fun withoutServerMetadata(row: JsonObject, localOnly: Set<String>): JsonObject {
        val localOnlyColumns = localOnly.mapTo(mutableSetOf(), Naming::snake)
        return JsonObject(row.filterKeys {
            it != "store_id" && it != "server_updated_at" && it !in localOnlyColumns
        })
    }

    private fun differingFields(
        expected: Map<String, JsonObject>,
        actual: Map<String, JsonObject>,
    ): Set<String> = (expected.keys + actual.keys).flatMapTo(mutableSetOf()) { id ->
        val expectedRow = expected[id]
        val actualRow = actual[id]
        when {
            expectedRow == null || actualRow == null -> setOf("<row>")
            else -> (expectedRow.keys + actualRow.keys).filterTo(mutableSetOf()) { key ->
                expectedRow[key] != actualRow[key]
            }
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: error("server row without $key")
    private fun parse(ts: String): Instant = OffsetDateTime.parse(ts).toInstant()

    /** One page, one transaction. Foreign keys are checked at the end so order inside a page doesn't matter. */
    private suspend fun apply(t: SyncTable, rows: List<JsonObject>) = db.withTransaction {
        val sdb = db.openHelper.writableDatabase
        sdb.execSQL("PRAGMA defer_foreign_keys = ON")
        val s = schema(t.name)
        val dirtyTracked = s.columns.any { it.name == "dirty" }
        for (row in rows) {
            val id = row.str("id")
            val exists = sdb.query("SELECT 1 FROM ${t.name} WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
            val localDirty = if (dirtyTracked && exists) {
                sdb.query("SELECT dirty FROM ${t.name} WHERE id = ?", arrayOf(id)).use { c ->
                    check(c.moveToFirst()) { "Row '$id' disappeared from '${t.name}' during sync." }
                    c.getInt(0)
                }
            } else null
            val values = RowMapper.toValues(row, s, t.localOnly).apply {
                if (dirtyTracked) put("dirty", 0)
            }
            when {
                !exists -> {
                    t.localDefaults.forEach { (k, v) -> if (v is Long) values.put(k, v) else values.put(k, v.toString()) }
                    // A clash (say, two phones issued the same invoice number) must stop the sync loudly, never drop a record quietly.
                    sdb.insert(t.name, android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, values)
                }
                t.kind == SyncKind.APPEND_ONLY -> Unit                    // already have it, and it never changes
                !dirtyTracked || localDirty == 0 -> sdb.update(t.name, android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, values, "id = ?", arrayOf(id))
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
        tables.asReversed().forEach { sdb.execSQL("DELETE FROM ${it.name}") }
        db.syncStateDao().clear()
    }

    private companion object { const val MAX_ROUNDS = 1_000 }
}
