package com.xcloak.storevia.core.util

import java.math.BigDecimal
import java.math.RoundingMode

/** Text-field <-> storage conversions. Money -> paise, quantity -> thousandths. No floating point. */
object Parsing {
    private fun decimal(s: String): BigDecimal? {
        val t = s.trim().replace(",", "")
        if (t.isEmpty()) return null
        return try { BigDecimal(t).takeIf { it.signum() >= 0 } } catch (_: NumberFormatException) { null }
    }

    fun moneyToPaise(s: String): Long? = decimal(s)
        ?.setScale(2, RoundingMode.HALF_UP)?.movePointRight(2)
        ?.let { runCatching { it.longValueExact() }.getOrNull() }

    /** Returns null for invalid input, or for a fraction when the unit doesn't allow decimals. */
    fun qtyToMilli(s: String, allowDecimal: Boolean): Long? {
        val d = decimal(s) ?: return null
        if (!allowDecimal && d.stripTrailingZeros().scale() > 0) return null
        return runCatching { d.setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact() }.getOrNull()
    }

    fun paiseToInput(p: Long): String =
        if (p % 100 == 0L) (p / 100).toString()
        else BigDecimal(p).movePointLeft(2).setScale(2).toPlainString()

    /** Always two decimals, for CSV and reports: 1000 -> "10.00". */
    fun paiseToPlain(p: Long): String = BigDecimal(p).movePointLeft(2).setScale(2).toPlainString()

    fun milliToInput(m: Long): String =
        BigDecimal(m).movePointLeft(3).stripTrailingZeros().toPlainString()
}
