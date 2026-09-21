package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

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
            withTimeoutOrNull(timeoutMs.milliseconds) {
                job.join()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ignore other exceptions during join
        } ?: run {
            // Timeout occurred, job might be stuck
            job.cancel()
        }
    }
}
