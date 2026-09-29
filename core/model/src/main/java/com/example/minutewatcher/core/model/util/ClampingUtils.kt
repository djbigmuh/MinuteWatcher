package com.example.minutewatcher.core.model.util

/**
 * Utility for clamping numeric values within a specified range.
 *
 * **Constraint Policy:**
 * The range [min, max] must be valid (min <= max). This is enforced via [require].
 *
 * **Hard Limits Rationale:**
 * Hard limits are defined in [com.example.minutewatcher.core.model.Constants] and are based on:
 * 1. **Logical Maxima:** e.g., minutes in a 31-day month (44640).
 * 2. **Precision Constraints:** e.g., traffic limit in GB (10240) to maintain Float precision within 1 MB.
 * 3. **SDK Contracts:** While TelephonyManager and SubscriptionInfo do not define usage limits,
 *    our application enforces these boundaries to prevent UI overflows and integer wrap-arounds
 *    when calculating remaining usage or progress bars.
 */
object ClampingUtils {
    /**
     * Clamps the given [value] between [min] and [max].
     * @throws IllegalArgumentException if min > max.
     */
    fun <T : Comparable<T>> clamp(
        value: T,
        min: T,
        max: T,
    ): T {
        require(min <= max) { "Clamping min ($min) cannot be greater than max ($max)" }
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }
}
