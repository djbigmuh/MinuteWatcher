package host.serv.djbigmuh.minutewatcher.core.common.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

private const val QUIESCENT_POLL_INTERVAL_MS = 10L

/**
 * Awaits until AppLogger has processed all pending log entries.
 *
 * @param timeoutMs maximum time to wait; on expiry the call fails with TimeoutCancellationException.
 */
suspend fun AppLogger.awaitQuiescent(timeoutMs: Long = 10_000L) {
    withTimeout(timeoutMs.milliseconds) {
        while (hasPendingLogs()) {
            delay(QUIESCENT_POLL_INTERVAL_MS)
        }
    }
}
