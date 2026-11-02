package com.xcloak.storevia.domain.purchase

import com.xcloak.storevia.core.database.dao.ReorderRow
import com.xcloak.storevia.core.util.Parsing

object ReorderPlanner {
    /**
     * Suggested quantity to buy (thousandths): enough to bring stock back up to TWICE the reorder level.
     * Rounded up to whole units (or half units for kg/litre). Always an editable suggestion, never an order.
     */
    fun suggestMilli(onHandMilli: Long, minStockMilli: Long, allowDecimal: Boolean): Long {
        val need = 2 * minStockMilli - onHandMilli
        if (need <= 0) return 0
        val step = if (allowDecimal) 500L else 1000L
        return (need + step - 1) / step * step
    }

    /** A plain-text list to send to a supplier over WhatsApp or SMS. */
    fun shareText(storeName: String, rows: List<ReorderRow>): String = buildString {
        appendLine("Reorder list - $storeName")
        rows.forEachIndexed { i, r ->
            val qty = suggestMilli(r.onHandMilli, r.minStockMilli, r.allowDecimal)
            appendLine("${i + 1}. ${r.name} - ${Parsing.milliToInput(qty)} ${r.unitSymbol}".trimEnd())
        }
        append("Thank you")
    }
}
