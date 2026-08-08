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
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): PeriodInfo {
        val safeDay = resetDay.coerceIn(1, MAX_DAYS_IN_MONTH)
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)

        val currentYearMonth = YearMonth.from(now)

        val resetDayActual = minOf(safeDay, currentYearMonth.lengthOfMonth())
        val startDate: ZonedDateTime
        if (now.dayOfMonth < resetDayActual) {
            // Period started last month
            val prevMonth = currentYearMonth.minusMonths(1)
            val prevResetDay = minOf(safeDay, prevMonth.lengthOfMonth())
            startDate = prevMonth.atDay(prevResetDay).atStartOfDay(zoneId)
        } else {
            // Period started this month
            startDate = currentYearMonth.atDay(resetDayActual).atStartOfDay(zoneId)
        }

        // End date is exactly one month from start date, adjusted for month length
        val nextMonth = YearMonth.from(startDate).plusMonths(1)
        val nextResetDay = minOf(safeDay, nextMonth.lengthOfMonth())
        val endDate = nextMonth.atDay(nextResetDay).atStartOfDay(zoneId)

        val totalDuration = ChronoUnit.MILLIS.between(startDate, endDate)
        val elapsed = ChronoUnit.MILLIS.between(startDate, now)
        val progress = if (totalDuration > 0) ((elapsed.toDouble() / totalDuration) * PERCENT_MAX).toInt() else 0

        // Accurate days remaining calculation
        val daysRemaining = ChronoUnit.DAYS.between(now.toLocalDate(), endDate.toLocalDate()).toInt()

        return PeriodInfo(
            startDate = startDate.toInstant().toEpochMilli(),
            endDate = endDate.toInstant().toEpochMilli(),
            daysRemaining = maxOf(0, daysRemaining),
            progressPercentage = progress.coerceIn(0, PERCENT_MAX),
        )
    }

    private const val PERCENT_MAX = 100
    private const val MAX_DAYS_IN_MONTH = 31
}
