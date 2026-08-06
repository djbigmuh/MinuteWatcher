package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.Flow

interface SimUsageRepository {
    fun getSimUsage(identity: String): Flow<SimUsage>

    suspend fun setMinutesUsed(
        identity: String,
        value: Int,
    )

    suspend fun incrementMinutesUsed(
        identity: String,
        delta: Int,
    )

    suspend fun setTrafficUsed(
        identity: String,
        valueMb: Float,
    )

    suspend fun incrementTrafficUsed(
        identity: String,
        deltaMb: Float,
    )
}
