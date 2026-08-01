package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

interface SimLimitsRepository {
    fun getMonthlyLimit(identity: String): Flow<Int>

    fun getTrafficLimit(identity: String): Flow<Int>

    suspend fun updateSimLimits(
        identity: String,
        minutesLimit: Int,
        trafficLimitMb: Int,
        rolloverEnabled: Boolean = false,
    )

    fun getAutoReset(identity: String): Flow<Boolean>

    fun getAutoResetDay(identity: String): Flow<Int>

    suspend fun setAutoReset(
        identity: String,
        day: Int,
        enabled: Boolean,
    )
}
