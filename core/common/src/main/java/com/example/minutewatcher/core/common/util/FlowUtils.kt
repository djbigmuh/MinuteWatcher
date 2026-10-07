package com.example.minutewatcher.core.common.util

import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Utility for WorkManager workers and ViewModels to safely fetch the first value from a Flow with a timeout.
 * This prevents tasks from hanging indefinitely if a Flow never emits.
 *
 * **WARNING:** If the Flow emits `null`, the result will be `null`. If the operation times out,
 * the result will also be `null`. To avoid ambiguity, this utility should preferably be used
 * with non-nullable flows.
 */
suspend fun <T> Flow<T>.firstWithTimeout(timeoutMillis: Long = Constants.Flow.DEFAULT_READ_TIMEOUT_MS): T? =
    withTimeoutOrNull(timeoutMillis.milliseconds) {
        this@firstWithTimeout.firstOrNull()
    }

/**
 * Overload that accepts [Duration].
 */
suspend fun <T> Flow<T>.firstWithTimeout(timeout: Duration): T? =
    withTimeoutOrNull(timeout) {
        this@firstWithTimeout.firstOrNull()
    }

/**
 * Fetches the first value from a Flow with a timeout or returns a default value if it times out.
 * Logs a warning message when a timeout occurs.
 */
suspend fun <T> Flow<T>.firstWithTimeoutOrDefault(
    timeoutMillis: Long,
    tag: String,
    defaultValue: T,
    key: String? = null,
): T {
    return withTimeoutOrNull(timeoutMillis.milliseconds) {
        this@firstWithTimeoutOrDefault.firstOrNull()
    } ?: defaultValue.also {
        val keyInfo = if (key != null) " for key: $key" else ""
        AppLogger.w("Timeout reading from Flow in $tag after ${timeoutMillis}ms$keyInfo. Returning default.")
    }
}

/**
 * Fetches the first value from a Flow with a timeout or returns null if it times out.
 * Logs a warning message when a timeout occurs.
 */
suspend fun <T> Flow<T>.firstWithTimeoutOrLog(
    timeoutMillis: Long,
    tag: String,
    key: String? = null,
): T? {
    return withTimeoutOrNull(timeoutMillis.milliseconds) {
        this@firstWithTimeoutOrLog.firstOrNull()
    } ?: null.also {
        val keyInfo = if (key != null) " for key: $key" else ""
        AppLogger.w("Timeout reading from Flow in $tag after ${timeoutMillis}ms$keyInfo. Returning null.")
    }
}
