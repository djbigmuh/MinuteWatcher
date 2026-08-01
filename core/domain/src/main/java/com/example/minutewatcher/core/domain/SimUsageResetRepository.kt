package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

interface SimUsageResetRepository {
    suspend fun resetSimUsage(identity: String)

    suspend fun resetSimUsageManual(identity: String)

    suspend fun resetSimUsageIfDue(
        identity: String,
        autoResetDay: Int,
        nowMillis: Long,
    ): Boolean

    fun getLastResetDate(identity: String): Flow<Long>

    suspend fun setLastResetDate(
        identity: String,
        value: Long,
    )

    fun getLastTotalBytes(identity: String): Flow<Long>

    suspend fun setLastTotalBytes(
        identity: String,
        bytes: Long,
    )
}
