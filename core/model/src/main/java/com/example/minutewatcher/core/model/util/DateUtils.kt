package com.example.minutewatcher.core.model.util

import java.time.ZoneId

object DateUtils {
    fun getMonthProgressPercentage(resetDay: Int): Int =
        BillingPeriodCalculator.calculate(resetDay, zoneId = ZoneId.systemDefault()).progressPercentage

    fun getDaysRemainingInMonth(resetDay: Int): Int =
        BillingPeriodCalculator.calculate(resetDay, zoneId = ZoneId.systemDefault()).daysRemaining

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
