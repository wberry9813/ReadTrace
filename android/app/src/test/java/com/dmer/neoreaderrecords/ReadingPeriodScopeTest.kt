package com.dmer.neoreaderrecords

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPeriodScopeTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun completeCalendarMonthUsesMonthlyRanking() {
        assertTrue(
            ReadingPeriodScope.coversWholeMonths(
                instant(2026, Calendar.AUGUST, 1, 0, 0, 0, 0),
                instant(2026, Calendar.AUGUST, 31, 23, 59, 59, 999),
                zone
            )
        )
    }

    @Test
    fun multipleCompleteMonthsUseMonthlyRanking() {
        assertTrue(
            ReadingPeriodScope.coversWholeMonths(
                instant(2026, Calendar.JULY, 1, 0, 0, 0, 0),
                instant(2026, Calendar.AUGUST, 31, 23, 59, 59, 999),
                zone
            )
        )
    }

    @Test
    fun partialMonthMustUseTrackedDailyBooks() {
        assertFalse(
            ReadingPeriodScope.coversWholeMonths(
                instant(2026, Calendar.AUGUST, 20, 0, 0, 0, 0),
                instant(2026, Calendar.AUGUST, 26, 23, 59, 59, 999),
                zone
            )
        )
    }

    private fun instant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        millisecond: Int
    ): Long {
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute, second)
            set(Calendar.MILLISECOND, millisecond)
        }.timeInMillis
    }
}
