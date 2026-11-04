package com.xcloak.storevia.feature.reports

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReportDateBoundsTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun customRangeIncludesTheEntireEndDate() {
        val start = LocalDate.of(2026, 11, 1)
        val end = LocalDate.of(2026, 11, 3)
        val (from, to) = reportDateBounds(Period.CUSTOM, end, ReportDateRange(start, end), zone)

        assertEquals(start.atStartOfDay(zone).toInstant().toEpochMilli(), from)
        assertEquals(end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), to)
    }

    @Test
    fun sevenDayRangeIncludesTodayAndPreviousSixDays() {
        val today = LocalDate.of(2026, 11, 4)
        val (from, to) = reportDateBounds(Period.LAST_7_DAYS, today, null, zone)

        assertEquals(today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli(), from)
        assertEquals(today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), to)
    }
}
