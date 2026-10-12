package com.example.minutewatcher.core.data

internal data class CallStartData(
    val startTimeResult: Result<Long>,
    val elapsedRealtimeResult: Result<Long>,
    val identity: String?,
    val currentIdentity: String?,
    val now: Long,
    val elapsedRealtime: Long,
    val uptime: Long,
)
