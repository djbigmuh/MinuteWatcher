package com.example.minutewatcher.core.model.util

object DateUtils {

    fun getMonthProgressPercentage(resetDay: Int): Int {
        return BillingPeriodCalculator.calculate(resetDay).progressPercentage
    }

    fun getDaysRemainingInMonth(resetDay: Int): Int {
        return BillingPeriodCalculator.calculate(resetDay).daysRemaining
    }

    fun shouldPerformAutoReset(lastResetDate: Long, resetDay: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val periodInfo = BillingPeriodCalculator.calculate(resetDay, nowMillis)
        return lastResetDate < periodInfo.startDate
    }
}
