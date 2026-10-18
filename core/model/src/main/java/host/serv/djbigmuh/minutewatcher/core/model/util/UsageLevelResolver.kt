package host.serv.djbigmuh.minutewatcher.core.model.util

import host.serv.djbigmuh.minutewatcher.core.model.Constants

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

    /**
     * Resolves usage level based on remaining amount and limit using Double arithmetic without integer truncation.
     * Comparison of thresholds is performed without integer truncation; result matches `Long`-overload
     * for all integer values representable in `Float`.
     */
    fun resolve(
        remaining: Float,
        limit: Float,
    ): UsageStatus {
        val limitD = limit.toDouble()
        val remainingD = remaining.toDouble()
        return when {
            limitD.isNaN() || limitD <= 0.0 -> UsageStatus.NORMAL
            remainingD.isNaN() || remainingD <= 0.0 -> UsageStatus.CRITICAL
            remainingD * PERCENT_MULTIPLIER < limitD * Constants.CRITICAL_THRESHOLD_PERCENT_INT -> UsageStatus.CRITICAL
            remainingD * PERCENT_MULTIPLIER < limitD * Constants.LOW_THRESHOLD_PERCENT_INT -> UsageStatus.LOW
            else -> UsageStatus.NORMAL
        }
    }
}
