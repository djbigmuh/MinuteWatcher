package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Generalized class for managing a data stream observation cycle.
 * Implements a job management pattern: cancel old, start new,
 * save the last flow for recovery, and support for controlled shutdown.
 *
 * **Thread Safety:** Safe to call from any context. All state modifications are protected by a [Mutex].
 * **Semantics:** Uses `collectLatest` which ensures only the latest value is processed,
 * cancelling previous value processing if a new one arrives.
 *
 * @param T The type of data being observed.
 * @param scope The [CoroutineScope] in which to launch observation jobs.
 * @param tag A tag used for logging.
 */
class FlowObservationScope<T>(
    private val scope: CoroutineScope,
    private val tag: String,
) {
    private val mutex = Mutex()
    private var lastFlow: Flow<T>? = null
    private var lastCollector: (suspend (T) -> Unit)? = null
    private var lastContext: CoroutineContext = EmptyCoroutineContext

    @Volatile
    private var _currentJob: Job? = null

    /**
     * The currently active observation job, if any.
     */
    val currentJob: Job? get() = _currentJob

    /**
     * Starts observing the [flow]. Cancels the previous job if it exists.
     *
     * @param flow The flow to observe.
     * @param context The [CoroutineContext] to use for the observation job.
     * @param collector The suspend function to process each emitted value.
     * @return The new [Job].
     */
    suspend fun observe(
        flow: Flow<T>,
        context: CoroutineContext = EmptyCoroutineContext,
        collector: suspend (T) -> Unit,
    ): Job =
        mutex.withLock {
            lastFlow = flow
            lastCollector = collector
            lastContext = context

            _currentJob?.cancel()
            val newJob =
                scope.launch(context) {
                    flow.collectLatest { collector(it) }
                }
            _currentJob = newJob
            newJob
        }

    /**
     * Restarts the observation using the last known flow and collector.
     *
     * @return The new [Job], or null if no flow or collector was previously registered.
     */
    suspend fun recover(): Job? =
        mutex.withLock {
            val flow = lastFlow
            val collector = lastCollector
            if ((flow != null) && (collector != null)) {
                _currentJob?.cancel()
                val newJob =
                    scope.launch(lastContext) {
                        flow.collectLatest { collector(it) }
                    }
                _currentJob = newJob
                newJob
            } else {
                AppLogger.w("FlowObservationScope [$tag]: recovery skipped, no flow or collector registered")
                null
            }
        }

    /**
     * Cancels the current job and waits for completion for no longer than [timeoutMs].
     * Clears all references (flow, collector, job) similar to [shutdown].
     *
     * @param timeoutMs Maximum time to wait for the job to complete, in milliseconds.
     * @return True if the job finished within the timeout, false otherwise.
     */
    suspend fun stopAndAwait(timeoutMs: Long): Boolean {
        val job =
            mutex.withLock {
                val j = _currentJob
                _currentJob = null
                lastFlow = null
                lastCollector = null
                lastContext = EmptyCoroutineContext
                j
            } ?: return true

        job.cancel()

        val completedInTime =
            withTimeoutOrNull(timeoutMs.milliseconds) {
                job.join()
                true
            } ?: false

        if (!completedInTime) {
            AppLogger.w("FlowObservationScope[$tag]: job did not terminate within ${timeoutMs}ms, forcing cancel")
            job.cancel()
        }

        return completedInTime
    }

    /**
     * Stops the current observation and clears all internal references.
     */
    suspend fun shutdown() {
        mutex.withLock {
            _currentJob?.cancel()
            _currentJob = null
            lastFlow = null
            lastCollector = null
            lastContext = EmptyCoroutineContext
        }
    }
}
