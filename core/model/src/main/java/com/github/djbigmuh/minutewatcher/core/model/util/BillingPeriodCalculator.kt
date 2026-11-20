package com.github.djbigmuh.minutewatcher.core.model.util

import com.github.djbigmuh.minutewatcher.core.model.Constants
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object BillingPeriodCalculator {
    /**
     * Information about a billing period.
     *
     * @property startDate Start timestamp of the billing period in epoch milliseconds.
     * @property endDate End timestamp of the billing period in epoch milliseconds.
     * @property daysRemaining Number of days remaining in the billing period.
     */
    data class PeriodInfo(
        val startDate: Long,
        val endDate: Long,
        val daysRemaining: Int,
    )

    fun calculate(
        resetDay: Int,
        nowMillis: Long,
        zoneId: ZoneId,
    ): PeriodInfo {
        val safeDay = resetDay.coerceIn(Constants.AutoReset.MIN_DAY, Constants.AutoReset.MAX_DAY)
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

        // Accurate days remaining calculation
        val daysRemaining = ChronoUnit.DAYS.between(now.toLocalDate(), endDate.toLocalDate()).toInt()

        return PeriodInfo(
            startDate = startDate.toInstant().toEpochMilli(),
            endDate = endDate.toInstant().toEpochMilli(),
            daysRemaining = maxOf(0, daysRemaining),
        )
    }
}
