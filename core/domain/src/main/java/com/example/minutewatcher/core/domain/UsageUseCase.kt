package com.example.minutewatcher.core.domain

interface UsageUseCase {
    suspend fun refreshOperatorInfo()

    suspend fun adjustMinutes(
        slot: Int,
        remainingMinutes: Int,
    )

    suspend fun adjustTraffic(
        slot: Int,
        remainingGb: Float,
    )
}
