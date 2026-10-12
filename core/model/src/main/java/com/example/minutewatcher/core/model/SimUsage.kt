package com.example.minutewatcher.core.model

/**
 * SIM usage statistics and configuration.
 *
 * @property identity Stable SIM identity.
 * @property slotIndex SIM slot index.
 * @property subscriptionId Subscription ID, or null if unavailable.
 * @property operator Operator name string.
 * @property minutesUsed Minutes used in current period.
 * @property minutesLimit Minutes limit for current period.
 * @property trafficUsedMb Traffic used in MB.
 * @property trafficLimitMb Traffic limit in MB.
 * @property manualAdjustment One-time manual adjustment of minutes.
 * @property trafficAdjustmentGb One-time manual adjustment of traffic in GB.
 * @property autoReset Whether automatic reset is enabled.
 * @property autoResetDay Day of month for automatic reset.
 * @property lastResetDate Timestamp of the last reset event.
 * @property rolloverEnabled Whether rollover of unused limits is enabled.
 */
data class SimUsage(
    val identity: String,
    val slotIndex: Int,
    val subscriptionId: Int?,
    val operator: String,
    val minutesUsed: Int,
    val minutesLimit: Int,
    /**
     * Traffic used in MB.
     * Note: Use Double for calculations to avoid precision loss, then convert back to Float for storage.
     */
    val trafficUsedMb: Float,
    val trafficLimitMb: Int,
    /**
     * One-time adjustment of remaining minutes for current billing period to synchronize with operator data.
     * Used for scenarios like buying/selling minutes, bonuses, SIM in another phone, or moving SIM to a new device.
     * Does NOT alter tariff plan: permanent plan changes are defined via [minutesLimit].
     * Shifts only effective limit of current period: `effectiveLimit = minutesLimit + manualAdjustment`.
     * Lifecycle: consumed on auto-reset — without rollover it is discarded (new period starts from [minutesLimit]);
     * with rollover included in carried-over balance (`carryover = limit + adjustment - used` as new adjustment).
     * Manual reset zeroes adjustment along with counters.
     */
    val manualAdjustment: Int,
    /**
     * One-time adjustment of remaining traffic (in GB) for current billing period to synchronize with operator data.
     * Uses the same lifecycle and rules as [manualAdjustment].
     */
    val trafficAdjustmentGb: Float,
    val autoReset: Boolean,
    val autoResetDay: Int,
    val lastResetDate: Long,
    val rolloverEnabled: Boolean = false,
)
