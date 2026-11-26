package com.github.djbigmuh.minutewatcher.core.common.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

private const val QUIESCENT_POLL_INTERVAL_MS = 10L

/**
 * Awaits until AppLogger has processed all pending log entries.
 *
 * @param timeoutMs maximum time to wait; on expiry the call fails with TimeoutCancellationException.
 */
suspend fun AppLogger.awaitQuiescent(timeoutMs: Long = 10_000L) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMs.milliseconds) {
            while (hasPendingLogs()) {
                delay(QUIESCENT_POLL_INTERVAL_MS.milliseconds)
            }
        }
    }
}
