package host.serv.djbigmuh.minutewatcher.core.model.util

/**
 * Centralized selection of usage statuses.
 */
object ColorProvider {
    /**
     * Gets usage status based on remaining and limit values without integer truncation of fractional values.
     */
    fun getUsageStatus(
        remaining: Float,
        limit: Float,
    ): UsageStatus = UsageLevelResolver.resolve(remaining, limit)
}
