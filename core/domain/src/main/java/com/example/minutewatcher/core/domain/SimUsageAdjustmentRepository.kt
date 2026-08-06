package com.example.minutewatcher.core.domain

interface SimUsageAdjustmentRepository {
    suspend fun setManualAdjustment(
        identity: String,
        value: Int,
    )

    suspend fun setTrafficAdjustment(
        identity: String,
        valueGb: Float,
    )

    suspend fun updateSimStats(
        identity: String,
        minutesUsed: Int,
        trafficUsedMb: Float,
        manualAdjustment: Int,
    )
}
