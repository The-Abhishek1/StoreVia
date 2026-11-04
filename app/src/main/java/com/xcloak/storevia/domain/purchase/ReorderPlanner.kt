package com.xcloak.storevia.domain.purchase

import com.xcloak.storevia.core.database.dao.ReorderRow
import com.xcloak.storevia.core.util.Parsing

object ReorderPlanner {
    /**
     * Suggested quantity to buy (thousandths): enough to bring stock back up to TWICE the reorder level.
     * Rounded up to whole units (or half units for kg/litre). Always an editable suggestion, never an order.
     */
    fun suggestMilli(onHandMilli: Long, minStockMilli: Long, allowDecimal: Boolean): Long {
        if (minStockMilli <= 0) return 0
        val target = if (minStockMilli > Long.MAX_VALUE / 2) Long.MAX_VALUE else minStockMilli * 2
        if (onHandMilli >= target) return 0
        val need = if (onHandMilli < 0 && target > Long.MAX_VALUE + onHandMilli) {
            Long.MAX_VALUE
        } else {
            target - onHandMilli
        }
        val step = if (allowDecimal) 500L else 1000L
        val units = need / step + if (need % step == 0L) 0 else 1
        return if (units > Long.MAX_VALUE / step) Long.MAX_VALUE / step * step else units * step
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
