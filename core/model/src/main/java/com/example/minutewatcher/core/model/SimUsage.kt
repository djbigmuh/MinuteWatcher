package com.example.minutewatcher.core.model

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
    val manualAdjustment: Int,
    /**
     * Traffic adjustment in GB.
     */
    val trafficAdjustmentGb: Float,
    val autoReset: Boolean,
    val autoResetDay: Int,
    val lastResetDate: Long,
    val rolloverEnabled: Boolean = false,
)
