package com.xcloak.storevia.core.money

object DiscountAllocator {
    /**
     * Splits [total] paise across items in proportion to [weights], to the paisa: the shares always add up to
     * [total], and no item receives more than its own weight (as long as total <= sum of weights).
     * Leftover paise go to the items with the largest fractional remainders (ties: earlier item first).
     */
    fun allocate(total: Long, weights: List<Long>): List<Long> {
        require(total >= 0 && weights.all { it >= 0 })
        val sum = weights.sum()
        if (total == 0L || sum == 0L) return List(weights.size) { 0L }

        val shares = weights.map { total * it / sum }.toMutableList()   // fits in Long for any realistic bill
        var left = total - shares.sum()
        val byRemainder = weights.indices.sortedByDescending { (total * weights[it]) % sum }
        var i = 0
        while (left > 0) { shares[byRemainder[i % byRemainder.size]] += 1; left--; i++ }
        return shares
    }
}
