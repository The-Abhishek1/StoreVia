package com.xcloak.storevia.core.money

/** Money as integer paise. Never use Float/Double for currency. */
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {
    operator fun plus(o: Money) = Money(paise + o.paise)
    operator fun minus(o: Money) = Money(paise - o.paise)
    operator fun unaryMinus() = Money(-paise)
    override fun compareTo(other: Money) = paise.compareTo(other.paise)

    /** Multiply by a quantity expressed in thousandths (1.250 kg = 1250), rounded half-up. */
    fun timesQtyMilli(qtyMilli: Long): Money = Money(Rounding.divHalfUp(paise * qtyMilli, 1000L))

    /** Rupees.paise, e.g. "₹1,234.50". Display only. */
    fun format(): String {
        val neg = paise < 0
        val abs = kotlin.math.abs(paise)
        val rupees = abs / 100
        val p = abs % 100
        val grouped = groupIndian(rupees.toString())
        return (if (neg) "-" else "") + "₹" + grouped + "." + p.toString().padStart(2, '0')
    }

    companion object {
        val ZERO = Money(0)
        fun ofRupees(r: Long) = Money(r * 100)
        private fun groupIndian(s: String): String {
            if (s.length <= 3) return s
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            return "$rest,$last3"
        }
    }
}

object Rounding {
    /** Integer division rounded half away from zero. */
    fun divHalfUp(num: Long, den: Long): Long {
        require(den > 0)
        return if (num >= 0) (num + den / 2) / den else -((-num + den / 2) / den)
    }
}
