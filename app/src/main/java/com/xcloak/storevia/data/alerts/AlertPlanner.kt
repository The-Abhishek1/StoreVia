package com.xcloak.storevia.data.alerts

import java.time.Duration
import java.time.LocalDateTime

/** What the daily check found. Counts only; the notifier turns them into text. */
data class AlertPlan(
    val expiringBatches: Int = 0,
    val expiringCostPaise: Long = 0,
    val expiredBatches: Int = 0,
    val expiredCostPaise: Long = 0,
    val lowStockProducts: Int = 0,
) {
    val hasExpiryAlert: Boolean get() = expiringBatches > 0 || expiredBatches > 0
    val hasLowStockAlert: Boolean get() = lowStockProducts > 0
    val isEmpty: Boolean get() = !hasExpiryAlert && !hasLowStockAlert
}

object AlertPlanner {
    /** Milliseconds from [now] until the next time the clock shows [hour]:00 (today if still ahead, otherwise tomorrow). */
    fun delayUntilNext(now: LocalDateTime, hour: Int): Long {
        require(hour in 0..23)
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
