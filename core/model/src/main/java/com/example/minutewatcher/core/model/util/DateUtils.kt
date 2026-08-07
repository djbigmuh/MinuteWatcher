package com.example.minutewatcher.core.model.util

import java.time.ZoneId

object DateUtils {
    fun getMonthProgressPercentage(
        resetDay: Int,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int = BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId).progressPercentage

    fun getDaysRemainingInMonth(
        resetDay: Int,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Int = BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId).daysRemaining
}
