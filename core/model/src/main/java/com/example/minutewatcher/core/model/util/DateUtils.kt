package com.example.minutewatcher.core.model.util

import java.time.ZoneId

object DateUtils {
    fun getMonthProgressPercentage(resetDay: Int, nowMillis: Long = System.currentTimeMillis()): Int =
        BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId = ZoneId.systemDefault()).progressPercentage

    fun getDaysRemainingInMonth(resetDay: Int, nowMillis: Long = System.currentTimeMillis()): Int =
        BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId = ZoneId.systemDefault()).daysRemaining
}
