package com.example.minutewatcher.core.model

data class SimUsage(
    val identity: String,
    val slotIndex: Int,
    val subscriptionId: Int?,
    val operator: String,
    val minutesUsed: Int,
    val minutesLimit: Int,
    val trafficUsed: Float,
    val trafficLimitMb: Int,
    val manualAdjustment: Int,
    val trafficAdjustment: Float,
    val autoReset: Boolean,
    val autoResetDay: Int,
    val lastResetDate: Long,
    val rolloverEnabled: Boolean = false,
)
