package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.Constants

/**
 * Resolver for usage level based on remaining amount and limit using integer arithmetic.
 */
object UsageLevelResolver {
    private const val PERCENT_MULTIPLIER = 100L

    fun resolve(
        remaining: Long,
        limit: Long,
    ): UsageStatus =
        when {
            limit <= 0L -> UsageStatus.NORMAL
            remaining <= 0L -> UsageStatus.CRITICAL
            remaining * PERCENT_MULTIPLIER < limit * Constants.CRITICAL_THRESHOLD_PERCENT_INT -> UsageStatus.CRITICAL
            remaining * PERCENT_MULTIPLIER < limit * Constants.LOW_THRESHOLD_PERCENT_INT -> UsageStatus.LOW
            else -> UsageStatus.NORMAL
        }
}
