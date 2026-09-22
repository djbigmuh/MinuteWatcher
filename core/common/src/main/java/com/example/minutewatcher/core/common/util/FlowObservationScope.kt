package com.example.minutewatcher.core.common.util

import com.example.minutewatcher.core.common.util.AppLogger
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

    private var lastFlow: Flow<T>? = null
    private var lastContext: CoroutineContext? = null
    private var lastCollector: (suspend (T) -> Unit)? = null
    private var isShutdown = false

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
    ): Job? =
        mutex.withLock {
            if (isShutdown) return null
            lastFlow = flow
            lastContext = context
            lastCollector = collector
            startObservationLocked()
        }

    /**
     * Restarts the last observation if it was active.
     */
    suspend fun recover(): Job? =
        mutex.withLock {
            if (isShutdown || lastFlow == null) return null
            AppLogger.d("FlowObservationScope($tag): recovery requested")
            startObservationLocked()
        }

    private fun startObservationLocked(): Job {
        val flow = requireNotNull(lastFlow) { "FlowObservationScope($tag): no flow registered for observation" }
        val collector = requireNotNull(lastCollector) { "FlowObservationScope($tag): no collector registered" }
        val context = lastContext ?: EmptyCoroutineContext
        _currentJob?.cancel()
        val newJob =
            scope.launch(context) {
                flow.collectLatest { collector(it) }
            }
        _currentJob = newJob
        return newJob
    }

    /**
     * Stops the current observation and waits for it to complete.
     *
     * @param timeoutMs Timeout in milliseconds to wait for the job to complete.
     * @return True if the job completed within the timeout, false otherwise.
     */
    suspend fun stopAndAwait(timeoutMs: Long): Boolean {
        val jobToWait =
            mutex.withLock {
                val job = _currentJob
                _currentJob = null
                job?.cancel()
                job
            } ?: return true

        return withTimeoutOrNull(timeoutMs) {
            jobToWait.join()
            true
        } ?: false
    }

    /**
     * Stops the current observation and clears all internal references.
     */
    suspend fun shutdown() {
        mutex.withLock {
            isShutdown = true
            _currentJob?.cancel()
            _currentJob = null
            lastFlow = null
            lastCollector = null
        }
    }
}
