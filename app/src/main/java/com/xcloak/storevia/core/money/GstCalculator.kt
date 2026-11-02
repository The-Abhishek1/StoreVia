package com.xcloak.storevia.core.money

/** Tax breakup for one invoice line. All values in paise. */
data class LineTax(
    val taxableValue: Long,
    val cgst: Long,
    val sgst: Long,
    val igst: Long,
    /** Amount the customer pays for the line (taxable + tax). */
    val lineTotal: Long,
) {
    val totalTax: Long get() = cgst + sgst + igst
}

object GstCalculator {
    /**
     * @param amountPaise   line amount AFTER line discount (price*qty - discount)
     * @param rateBp        GST rate in basis points (500 = 5%, 1800 = 18%)
     * @param taxInclusive  true if [amountPaise] already includes GST
     * @param interState    true -> IGST, false -> CGST + SGST (SGST gets the odd paisa)
     */
    fun calculate(amountPaise: Long, rateBp: Int, taxInclusive: Boolean, interState: Boolean): LineTax {
        require(rateBp >= 0)
        val taxable: Long
        val tax: Long
        if (taxInclusive) {
            taxable = Rounding.divHalfUp(amountPaise * 10_000L, 10_000L + rateBp)
            tax = amountPaise - taxable
        } else {
            taxable = amountPaise
            tax = Rounding.divHalfUp(amountPaise * rateBp, 10_000L)
        }
        val total = taxable + tax
        return if (interState) {
            LineTax(taxable, 0, 0, tax, total)
        } else {
            val cgst = tax / 2
            LineTax(taxable, cgst, tax - cgst, 0, total)
        }
    }

    /** Invoice total rounded to the nearest rupee. Returns (roundedTotal, roundOff). */
    fun roundToRupee(totalPaise: Long): Pair<Long, Long> {
        val rounded = Rounding.divHalfUp(totalPaise, 100L) * 100L
        return rounded to (rounded - totalPaise)
    }
}
