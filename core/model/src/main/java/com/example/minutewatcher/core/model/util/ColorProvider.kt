package com.example.minutewatcher.core.model.util

/**
 * Centralized selection of usage statuses.
 */
object ColorProvider {
    fun getUsageStatus(
        remaining: Float,
        limit: Float,
    ): UsageStatus = UsageLevelResolver.resolve(remaining.toLong(), limit.toLong())
}
