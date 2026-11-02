package com.xcloak.storevia.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AlertPlannerTest {
    private val hour = 3_600_000L

    @Test fun laterTodayWhenTheHourIsStillAhead() =
        assertEquals(2 * hour, AlertPlanner.delayUntilNext(LocalDateTime.of(2026, 10, 22, 7, 0), 9))

    @Test fun tomorrowWhenTheHourHasPassed() =
        assertEquals(23 * hour, AlertPlanner.delayUntilNext(LocalDateTime.of(2026, 10, 22, 10, 0), 9))

    @Test fun exactlyOnTheHourWaitsAFullDay() =
        assertEquals(24 * hour, AlertPlanner.delayUntilNext(LocalDateTime.of(2026, 10, 22, 9, 0), 9))

    @Test fun crossesMidnightAndMonthEnd() =
        assertEquals(10 * hour, AlertPlanner.delayUntilNext(LocalDateTime.of(2026, 10, 31, 23, 0), 9))

    @Test fun planFlags() {
        assertTrue(AlertPlan().isEmpty)
        assertTrue(AlertPlan(expiredBatches = 1).hasExpiryAlert)
        assertTrue(AlertPlan(expiringBatches = 2).hasExpiryAlert)
        assertFalse(AlertPlan(lowStockProducts = 3).hasExpiryAlert)
        assertTrue(AlertPlan(lowStockProducts = 3).hasLowStockAlert)
    }
}
