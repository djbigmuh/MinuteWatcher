package com.example.minutewatcher.core.model.util

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object BillingPeriodCalculator {
    data class PeriodInfo(
        val startDate: Long,
        val endDate: Long,
        val daysRemaining: Int,
        val progressPercentage: Int,
    )

    fun calculate(
        resetDay: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): PeriodInfo {
        val zoneId = ZoneId.systemDefault()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)

        val currentYearMonth = YearMonth.from(now)

        val startDate: ZonedDateTime
        if (now.dayOfMonth < resetDay) {
            // Period started last month
            val prevMonth = currentYearMonth.minusMonths(1)
            val prevResetDay = minOf(resetDay, prevMonth.lengthOfMonth())
            startDate = prevMonth.atDay(prevResetDay).atStartOfDay(zoneId)
        } else {
            // Period started this month
            val resetDayActual = minOf(resetDay, currentYearMonth.lengthOfMonth())
            startDate = currentYearMonth.atDay(resetDayActual).atStartOfDay(zoneId)
        }

        // End date is exactly one month from start date, adjusted for month length
        val nextMonth = YearMonth.from(startDate).plusMonths(1)
        val nextResetDay = minOf(resetDay, nextMonth.lengthOfMonth())
        val endDate = nextMonth.atDay(nextResetDay).atStartOfDay(zoneId)

        val totalDuration = ChronoUnit.MILLIS.between(startDate, endDate)
        val elapsed = ChronoUnit.MILLIS.between(startDate, now)
        val progress = if (totalDuration > 0) ((elapsed.toDouble() / totalDuration) * 100).toInt() else 0

        // Accurate days remaining calculation
        val daysRemaining = ChronoUnit.DAYS.between(now.toLocalDate(), endDate.toLocalDate()).toInt()

        return PeriodInfo(
            startDate = startDate.toInstant().toEpochMilli(),
            endDate = endDate.toInstant().toEpochMilli(),
            daysRemaining = maxOf(0, daysRemaining),
            progressPercentage = progress.coerceIn(0, 100),
        )
    }
}
