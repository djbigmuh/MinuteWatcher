package com.github.djbigmuh.minutewatcher.core.model.util

import java.time.ZoneId

object DateUtils {
    fun getDaysRemainingInMonth(
        resetDay: Int,
        nowMillis: Long,
        zoneId: ZoneId,
    ): Int = BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId).daysRemaining
}
