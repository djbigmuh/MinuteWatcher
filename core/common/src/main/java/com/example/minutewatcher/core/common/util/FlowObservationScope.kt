package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Generalized class for managing a data stream observation cycle.
 * Implements a job management pattern: cancel old, start new,
 * and support for controlled shutdown.
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
            _currentJob?.cancel()
            val newJob =
                scope.launch(context) {
                    flow.collectLatest { collector(it) }
                }
            _currentJob = newJob
            newJob
        }

    /**
     * Stops the current observation and clears all internal references.
     */
    suspend fun shutdown() {
        mutex.withLock {
            _currentJob?.cancel()
            _currentJob = null
        }
    }
}
