package com.example.minutewatcher.core.model

/**
 * Represents an active call session.
 */
data class CallSession(
    val startTime: Long,
    val bootTime: Long,
    val identity: String? = null,
)
