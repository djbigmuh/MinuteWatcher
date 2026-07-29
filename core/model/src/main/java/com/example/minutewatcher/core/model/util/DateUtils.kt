package com.example.minutewatcher.core.model.util

import java.time.ZoneId

object DateUtils {
    fun getMonthProgressPercentage(resetDay: Int, nowMillis: Long = System.currentTimeMillis()): Int =
        BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId = ZoneId.systemDefault()).progressPercentage

    fun getDaysRemainingInMonth(resetDay: Int, nowMillis: Long = System.currentTimeMillis()): Int =
        BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId = ZoneId.systemDefault()).daysRemaining

    fun shouldPerformAutoReset(
        lastResetDate: Long,
        resetDay: Int,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val periodInfo = BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId)
        return lastResetDate < periodInfo.startDate
    }
}
