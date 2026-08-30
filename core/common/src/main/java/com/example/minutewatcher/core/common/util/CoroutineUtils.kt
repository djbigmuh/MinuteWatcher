package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Utility function to execute a block and catch exceptions while ensuring
 * that [CancellationException] is always rethrown.
 *
 * @param block The suspend block to execute.
 * @param onError Callback for handling non-cancellation exceptions.
 * @return The result of the block or null if an exception occurred.
 */
suspend inline fun <T> catchingCancellation(
    crossinline block: suspend () -> T,
    crossinline onError: suspend (Exception) -> Unit,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
        null
    }

/**
 * Cancels the job and waits for its completion with a timeout.
 * If the job does not complete within the timeout, it is cancelled again (forced).
 *
 * @param timeoutMs Timeout in milliseconds.
 */
suspend fun Job?.cancelAndJoinWithTimeout(timeoutMs: Long) {
    this?.let { job ->
        job.cancel()
        try {
            withTimeoutOrNull(timeoutMs) {
                job.join()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignore other exceptions during join
        } ?: run {
            // Timeout occurred, job might be stuck
            job.cancel()
        }
    }
}
