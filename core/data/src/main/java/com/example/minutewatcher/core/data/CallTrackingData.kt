package com.example.minutewatcher.core.data

internal data class CallStartData(
    val startTimeResult: Result<Long>,
    val bootTimeResult: Result<Long>,
    val identity: String?,
    val currentIdentity: String?,
    val now: Long,
    val bootTime: Long,
    val uptime: Long,
)

internal data class CallEndData(
    val startTimeResult: Result<Long>,
    val savedBootTimeResult: Result<Long>,
    val savedIdentityResult: Result<String?>,
    val savedUptimeResult: Result<Long>,
    val currentBootTime: Long,
    val currentUptime: Long,
)
