package com.example.minutewatcher.core.common.util

import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private class Emission<T>(
    val value: T,
)

/**
 * Utility for WorkManager workers and ViewModels to safely fetch the first value
 * from a Flow with a timeout. This prevents tasks from hanging indefinitely if a Flow never emits.
 *
 * @return The first emitted value (including `null` if the Flow emits `null`).
 * Returns `null` if the operation times out or the Flow completes without emitting any value.
 */
suspend fun <T> Flow<T>.firstWithTimeout(timeoutMillis: Long = Constants.Flow.DEFAULT_READ_TIMEOUT_MS): T? =
    withTimeoutOrNull(timeoutMillis.milliseconds) {
        this@firstWithTimeout.map { Emission(it) }.firstOrNull()
    }?.value

/**
 * Overload that accepts [Duration].
 *
 * @return The first emitted value (including `null` if the Flow emits `null`).
 * Returns `null` if the operation times out or the Flow completes without emitting any value.
 */
suspend fun <T> Flow<T>.firstWithTimeout(timeout: Duration): T? =
    withTimeoutOrNull(timeout) {
        this@firstWithTimeout.map { Emission(it) }.firstOrNull()
    }?.value

/**
 * Fetches the first value from a Flow with a timeout or returns a default value
 * if it times out, completes without emission, or emits `null`.
 * Logs a warning message when a timeout occurs (not when emitting `null`).
 *
 * @return The first emitted value.
 * Returns [defaultValue] if the operation times out, completes without emission, or emits `null`.
 */
suspend fun <T> Flow<T>.firstWithTimeoutOrDefault(
    timeoutMillis: Long,
    tag: String,
    defaultValue: T,
    key: String? = null,
): T {
    val emission =
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            this@firstWithTimeoutOrDefault.map { Emission(it) }.firstOrNull()
        }
    return if (emission != null) {
        emission.value ?: defaultValue
    } else {
        val keyInfo = if (key != null) " for key: $key" else ""
        AppLogger.w(
            "Timeout reading from Flow in $tag after ${timeoutMillis}ms$keyInfo. " +
                "Returning default.",
        )
        defaultValue
    }
}

/**
 * Fetches the first value from a Flow with a timeout or returns null
 * if it times out or completes without emission.
 * Logs a warning message when a timeout occurs.
 *
 * @return The first emitted value (including `null` if the Flow emits `null`).
 * Returns `null` if the operation times out or the Flow completes without emitting any value.
 */
suspend fun <T> Flow<T>.firstWithTimeoutOrLog(
    timeoutMillis: Long,
    tag: String,
    key: String? = null,
): T? {
    val emission =
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            this@firstWithTimeoutOrLog.map { Emission(it) }.firstOrNull()
        }
    return if (emission != null) {
        emission.value
    } else {
        val keyInfo = if (key != null) " for key: $key" else ""
        AppLogger.w(
            "Timeout reading from Flow in $tag after ${timeoutMillis}ms$keyInfo. " +
                "Returning null.",
        )
        null
    }
}
