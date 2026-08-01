package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

interface SimNotificationFlagsRepository {
    fun getNotifiedLowMinutes(identity: String): Flow<Boolean>

    suspend fun setNotifiedLowMinutes(
        identity: String,
        notified: Boolean,
    )

    fun getNotifiedLowTraffic(identity: String): Flow<Boolean>

    suspend fun setNotifiedLowTraffic(
        identity: String,
        notified: Boolean,
    )

    fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean>

    suspend fun setNotifiedExhaustedMinutes(
        identity: String,
        notified: Boolean,
    )

    fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean>

    suspend fun setNotifiedExhaustedTraffic(
        identity: String,
        notified: Boolean,
    )
}
