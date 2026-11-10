package com.xcloak.storevia.feature.home

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal suspend fun fetchCloudStoreRows(
    client: SupabaseClient,
    storeId: String,
    tables: Set<String>,
): Map<String, List<JsonObject>> = coroutineScope {
    tables.map { table ->
        async {
            val rows = mutableListOf<JsonObject>()
            var offset = 0L
            while (true) {
                val page = client.from(table).select {
                    filter { eq("store_id", storeId) }
                    order("id", Order.ASCENDING)
                    range(offset, offset + CLOUD_PAGE_SIZE - 1L)
                }.decodeList<JsonObject>()
                rows += page
                if (page.size < CLOUD_PAGE_SIZE) break
                offset += CLOUD_PAGE_SIZE
            }
            table to rows
        }
    }.awaitAll().toMap()
}

internal const val CLOUD_PAGE_SIZE = 500

internal fun JsonObject.cloudString(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        ?.takeUnless { it == "null" }

internal fun JsonObject.cloudLong(key: String): Long? =
    runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

internal fun JsonObject.cloudInt(key: String): Int? = cloudLong(key)?.toInt()

internal fun JsonObject.cloudFlag(key: String): Boolean = cloudLong(key) == 1L
