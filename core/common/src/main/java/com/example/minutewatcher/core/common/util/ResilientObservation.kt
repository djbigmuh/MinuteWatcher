package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

private const val INITIAL_RETRY_DELAY_MS = 1000L
private const val MAX_RETRY_DELAY_MS = 30000L
private const val MAX_BACKOFF_EXPONENT = 5

/**
 * Extension operator on [Flow] that provides resilient collection with exponential retry backoff.
 *
 * Emits source values without modification. If an error occurs, retries collection after a delay.
 * If the source flow completes normally (which is unexpected for continuous observation flows),
 * invokes [onSourceCompleted] and completes the resulting flow.
 *
 * @param label Identifier tag used in log messages.
 * @param logThreshold Number of consecutive errors required before calling [onPersistentFailure] and logging errors.
 * @param onPersistentFailure Callback invoked on every error when consecutive error count reaches or exceeds [logThreshold].
 * @param onRecovered Callback invoked once upon the first emission after a series of errors.
 * @param onSourceCompleted Callback invoked if the source flow completes normally without throwing.
 */
fun <T> Flow<T>.observeWithRetry(
    label: String,
    logThreshold: Int,
    onPersistentFailure: (cause: Throwable) -> Unit,
    onRecovered: () -> Unit,
    onSourceCompleted: () -> Unit,
): Flow<T> =
    flow {
        var consecutiveErrors = 0
        while (true) {
            try {
                this@observeWithRetry.collect { value ->
                    if (consecutiveErrors > 0) {
                        AppLogger.d("$label recovered after $consecutiveErrors errors")
                        consecutiveErrors = 0
                        onRecovered()
                    }
                    emit(value)
                }
                AppLogger.w("$label completed normally (unexpected)")
                onSourceCompleted()
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                val exponent = (consecutiveErrors - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
                val delayMs = (INITIAL_RETRY_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_RETRY_DELAY_MS)

                val isTerminalLike = consecutiveErrors >= logThreshold
                val logMessage = "$label (attempt $consecutiveErrors), restarting in ${delayMs}ms"

                if (isTerminalLike) {
                    if ((consecutiveErrors % logThreshold) == 0) {
                        AppLogger.e(logMessage, e)
                    }
                    onPersistentFailure(e)
                } else {
                    AppLogger.w(logMessage)
                }
                delay(delayMs.milliseconds)
            }
        }
    }
