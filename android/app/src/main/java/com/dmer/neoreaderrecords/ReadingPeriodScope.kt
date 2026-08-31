package com.dmer.neoreaderrecords

import java.util.Calendar
import java.util.TimeZone

internal object ReadingPeriodScope {
    fun coversWholeMonths(startMs: Long, endMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
        if (startMs > endMs) return false
        val start = Calendar.getInstance(timeZone).apply { timeInMillis = startMs }
        if (
            start.get(Calendar.DAY_OF_MONTH) != 1 ||
            start.get(Calendar.HOUR_OF_DAY) != 0 ||
            start.get(Calendar.MINUTE) != 0 ||
            start.get(Calendar.SECOND) != 0 ||
            start.get(Calendar.MILLISECOND) != 0
        ) {
            return false
        }

        val expectedEnd = Calendar.getInstance(timeZone).apply {
            timeInMillis = startMs
            add(Calendar.MONTH, 1)
            while (timeInMillis <= endMs) add(Calendar.MONTH, 1)
            add(Calendar.MILLISECOND, -1)
        }
        return expectedEnd.timeInMillis == endMs
    }
}
