package com.xcloak.storevia.core.sync

/**
 * MUTABLE: an editable row (a product, a customer). If two phones edit the same row the last one to reach the
 * server wins, and a row with unsent local edits is never overwritten by a pull.
 * APPEND_ONLY: written once and never changed (a stock movement, a payment, a sale line). Two phones can never
 * conflict on these, which is why stock quantities are rebuilt from the ledger rather than copied.
 */
enum class SyncKind { MUTABLE, APPEND_ONLY }

/**
 * @param localOnly columns that belong to this phone: never sent, never overwritten by a pull
 * @param localDefaults values for [localOnly] columns when a row first arrives from the server
 */
data class SyncTable(
    val name: String,
    val kind: SyncKind,
    val localOnly: Set<String> = emptySet(),
    val localDefaults: Map<String, Any> = emptyMap(),
    /** Tables that must already be present before rows in this table are pulled. */
    val dependsOn: Set<String> = emptySet(),
)

object SyncTables {
    /** Parents before children, so foreign keys and references in held-cart JSON are satisfied while pulling. */
    val all: List<SyncTable> = listOf(
        SyncTable("store", SyncKind.MUTABLE, localOnly = setOf("nextInvoiceNo"), localDefaults = mapOf("nextInvoiceNo" to 1L)),
        SyncTable("branches", SyncKind.MUTABLE, localOnly = setOf("selectedOnDevice"), localDefaults = mapOf("selectedOnDevice" to false)),
        SyncTable("units", SyncKind.MUTABLE),
        SyncTable("categories", SyncKind.MUTABLE),
        SyncTable("products", SyncKind.MUTABLE),
        SyncTable("batches", SyncKind.APPEND_ONLY),
        SyncTable("recipe_components", SyncKind.MUTABLE, dependsOn = setOf("products")),
        SyncTable("serial_numbers", SyncKind.MUTABLE, dependsOn = setOf("products", "batches")),
        SyncTable("product_barcodes", SyncKind.MUTABLE, dependsOn = setOf("products")),
        SyncTable("suppliers", SyncKind.MUTABLE),
        SyncTable("customers", SyncKind.MUTABLE),
        // Held-cart items are JSON-serialized CartItems and may reference products and batches.
        // Their tombstones are synced when a cart is resumed or discarded.
        SyncTable("held_carts", SyncKind.MUTABLE, dependsOn = setOf("customers", "products", "batches")),
        // PINs and lock-out state never leave the phone: a cashier's phone must not hold the owner's PIN hash.
        SyncTable("staff", SyncKind.MUTABLE,
            localOnly = setOf("pinHash", "pinSalt", "pinIterations", "failedAttempts", "lockedUntil"),
            localDefaults = mapOf("pinIterations" to 0L, "failedAttempts" to 0L, "lockedUntil" to 0L)),
        SyncTable("sales", SyncKind.MUTABLE),
        SyncTable("sale_lines", SyncKind.APPEND_ONLY, dependsOn = setOf("sales")),
        SyncTable("payments", SyncKind.APPEND_ONLY),
        SyncTable("sale_returns", SyncKind.APPEND_ONLY),
        SyncTable("sale_return_lines", SyncKind.APPEND_ONLY, dependsOn = setOf("sale_returns")),
        SyncTable("stock_movements", SyncKind.APPEND_ONLY),
        SyncTable("customer_ledger", SyncKind.APPEND_ONLY),
        SyncTable("purchases", SyncKind.MUTABLE, dependsOn = setOf("suppliers")),
        SyncTable("purchase_lines", SyncKind.APPEND_ONLY, dependsOn = setOf("purchases")),
        SyncTable("supplier_ledger", SyncKind.APPEND_ONLY),
        SyncTable("audit_log", SyncKind.APPEND_ONLY),
    )
}

object Naming {
    /** qtyOnHandMilli -> qty_on_hand_milli. The server uses snake_case; the phone's columns are camelCase. */
    fun snake(name: String): String = buildString {
        name.forEach { c -> if (c.isUpperCase()) { append('_'); append(c.lowercaseChar()) } else append(c) }
    }
}
