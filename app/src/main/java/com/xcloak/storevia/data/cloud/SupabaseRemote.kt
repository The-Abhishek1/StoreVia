package com.xcloak.storevia.data.cloud

import com.xcloak.storevia.core.sync.SyncRemote
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonObject

/**
 * The real server. Row Level Security on the tables decides what each signed-in person may read and write;
 * the store_id filter here only narrows the query. Written against supabase-kt 3.x: check the `or`/`and` filter
 * builders and `upsert` options against the library's docs when you first compile.
 */
class SupabaseRemote(private val client: SupabaseClient, private val storeId: String) : SyncRemote {

    override suspend fun pull(table: String, afterTs: String?, afterId: String, limit: Int): List<JsonObject> =
        try {
            client.from(table).select {
                filter {
                    eq("store_id", storeId)
                    if (afterTs != null) {
                        or {
                            gt("server_updated_at", afterTs)
                            and {
                                eq("server_updated_at", afterTs)
                                gt("id", afterId)
                            }
                        }
                    }
                }
                order("server_updated_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                limit(limit.toLong())
            }.decodeList<JsonObject>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Downloading cloud table '$table' failed.", e)
        }

    override suspend fun push(table: String, rows: List<JsonObject>, ignoreDuplicates: Boolean) {
        if (rows.isEmpty()) return
        try {
            client.from(table).upsert(rows) {
                onConflict = "id"
                this.ignoreDuplicates = ignoreDuplicates
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Uploading cloud table '$table' failed.", e)
        }
    }
}
