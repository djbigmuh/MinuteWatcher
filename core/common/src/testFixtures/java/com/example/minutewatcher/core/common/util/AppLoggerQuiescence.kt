package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Awaits until AppLogger has processed all pending log entries.
 */
suspend fun AppLogger.awaitQuiescentForTest(timeoutSeconds: Long = 10L): Boolean {
    withTimeout(timeoutSeconds.seconds) {
        while (hasPendingLogs()) {
            delay(10L)
        }
    }
    return true
}

/**
 * Awaits until AppLogger has processed all pending log entries (compatibility overload).
 */
suspend fun AppLogger.awaitQuiescent(timeoutMs: Long = 10_000L): Boolean = awaitQuiescentForTest(timeoutMs / 1000L)
