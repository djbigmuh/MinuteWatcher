package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owner of a single observation job: starts with atomic replacement of the previous job,
 * and limited-time stop. Does not contain retry or status logic.
 *
 * Thread-safe: all operations on the job are atomic ([AtomicReference]),
 * and mutexes are not held during suspend operations.
 */
class ObservationHandle(
    private val scope: CoroutineScope,
    private val tag: String,
) {
    private val _currentJob = AtomicReference<Job?>(null)

    /** Current job, if any. */
    val currentJob: Job? get() = _currentJob.get()

    /**
     * Cancels the previous job (if any) and starts a new one:
     * scope.launch { flow.collect { collector(it) } }.
     * Returns the new job. Atomic replacement: getAndSet + cancel old.
     */
    fun <T> start(
        flow: Flow<T>,
        collector: suspend (T) -> Unit,
    ): Job {
        val newJob =
            scope.launch {
                flow.collect { collector(it) }
            }
        _currentJob.getAndSet(newJob)?.cancel()
        return newJob
    }

    /**
     * Cancels the current job and waits for completion for no longer than [timeoutMs]
     * ([withTimeoutOrNull] + join; upon timeout — second cancel, log).
     * Non-suspend state is always cleared. Returns true if the job finished in time.
     */
    suspend fun stopAndAwait(timeoutMs: Long): Boolean {
        val job = _currentJob.getAndSet(null) ?: return true
        job.cancel()

        val completedInTime =
            withTimeoutOrNull(timeoutMs.milliseconds) {
                job.join()
                true
            } ?: false

        if (!completedInTime) {
            AppLogger.w("ObservationHandle[$tag]: job did not terminate within ${timeoutMs}ms, forcing cancel")
            job.cancel()
        }

        return completedInTime
    }
}
