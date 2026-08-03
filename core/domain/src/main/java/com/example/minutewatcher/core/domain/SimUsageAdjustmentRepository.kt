package com.example.minutewatcher.core.domain

interface SimUsageAdjustmentRepository {
    suspend fun setManualAdjustment(
        identity: String,
        value: Int,
    )

    suspend fun setTrafficAdjustment(
        identity: String,
        value: Float,
    )

    suspend fun updateSimStats(
        identity: String,
        minutesUsed: Int,
        trafficUsed: Float,
        manualAdjustment: Int,
    )
}
