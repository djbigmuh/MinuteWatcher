package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.Constants

/**
 * Централизованный выбор статусов использования.
 */
object ColorProvider {
    fun getUsageStatus(
        remaining: Float,
        limit: Float,
    ): UsageStatus =
        when {
            limit <= 0 -> UsageStatus.NORMAL
            remaining <= 0 -> UsageStatus.CRITICAL
            remaining < limit * Constants.CRITICAL_REMAINING_THRESHOLD_PERCENT -> UsageStatus.CRITICAL
            remaining < limit * Constants.LOW_REMAINING_THRESHOLD_PERCENT -> UsageStatus.LOW
            else -> UsageStatus.NORMAL
        }
}
