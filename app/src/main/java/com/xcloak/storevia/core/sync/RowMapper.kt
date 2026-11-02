package com.xcloak.storevia.core.sync

import android.content.ContentValues
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

class Column(val name: String, val affinity: String)

/** The columns of a table, read from SQLite itself so this never drifts from the entities. */
class TableSchema(val table: String, val columns: List<Column>) {
    companion object {
        fun load(db: SupportSQLiteDatabase, table: String): TableSchema {
            val cols = mutableListOf<Column>()
            db.query("PRAGMA table_info($table)").use { c -> while (c.moveToNext()) cols += Column(c.getString(1), c.getString(2).uppercase()) }
            return TableSchema(table, cols)
        }
    }
}

object RowMapper {
    /** "dirty" is bookkeeping for this phone only and never travels. */
    private fun skip(name: String, exclude: Set<String>) = name == "dirty" || name in exclude

    fun toJson(c: Cursor, schema: TableSchema, exclude: Set<String>): JsonObject {
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (col in schema.columns) {
            if (skip(col.name, exclude)) continue
            val i = c.getColumnIndexOrThrow(col.name)
            m[Naming.snake(col.name)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JsonNull
                Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                else -> JsonPrimitive(c.getString(i))
            }
        }
        return JsonObject(m)
    }

    fun toValues(json: JsonObject, schema: TableSchema, exclude: Set<String>): ContentValues {
        val v = ContentValues()
        for (col in schema.columns) {
            if (skip(col.name, exclude)) continue
            val el = json[Naming.snake(col.name)] ?: continue      // a column the server doesn't have: keep the default
            if (el is JsonNull) { v.putNull(col.name); continue }
            val p = el as JsonPrimitive
            when {
                p.isString -> v.put(col.name, p.content)
                col.affinity.contains("INT") -> v.put(col.name, p.longOrNull ?: p.booleanOrNull?.let { if (it) 1L else 0L } ?: p.doubleOrNull?.toLong() ?: 0L)
                col.affinity.contains("REAL") || col.affinity.contains("FLOA") || col.affinity.contains("DOUB") -> v.put(col.name, p.doubleOrNull ?: 0.0)
                else -> v.put(col.name, p.content)
            }
        }
        return v
    }
}
