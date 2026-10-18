package host.serv.djbigmuh.minutewatcher.core.common.util

import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
 * Observation mode for [FlowObservationScope].
 */
enum class ObservationMode {
    /**
     * Processes only the latest value emitted by the flow, cancelling previous processing
     * when a new value arrives (equivalent to `collectLatest`).
     */
    LATEST,

    /**
     * Processes every emitted value completely in sequence (equivalent to `collect`).
     */
    COMPLETE,
}

/**
 * Generalized class for managing a data stream observation cycle.
 * Implements a job management pattern: cancel old, start new,
 * and support for controlled shutdown.
 *
 * **Thread Safety:** Safe to call from any context. All state modifications are protected by a [Mutex].
 * **Semantics:** Controlled by [ObservationMode] (`LATEST` uses `collectLatest`, `COMPLETE` uses `collect`).
 * **Handover Invariant:** Handover between observers is performed sequentially.
 * Waiting for the completion of the previous observer is bounded by [Constants.Flow.SHORT_TIMEOUT_MS].
 * If the previous observer fails to complete within this timeout, the new observer launches
 * without further waiting, logging the event.
 * **Reference Cleanup:** No references to [Flow], [CoroutineContext], or collector lambda are stored as fields.
 * Arguments are passed directly to execution and cleared upon job completion, avoiding memory leaks.
 *
 * @param T The type of data being observed.
 * @param scope The [CoroutineScope] in which to launch observation jobs.
 * @param tag A tag used for logging.
 * @param mode The [ObservationMode] determining collection semantics.
 */
class FlowObservationScope<T>(
    private val scope: CoroutineScope,
    private val tag: String,
    private val mode: ObservationMode = ObservationMode.LATEST,
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
            startObservationLocked(flow, context, collector)
        }

    private suspend fun startObservationLocked(
        flow: Flow<T>,
        context: CoroutineContext,
        collector: suspend (T) -> Unit,
    ): Job {
        val previous = _currentJob
        _currentJob = null
        if (previous != null) {
            val completed =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    previous.cancelAndJoin()
                }
            if (completed == null) {
                AppLogger.w(
                    "FlowObservationScope($tag): previous observer did not complete " +
                        "within ${Constants.Flow.SHORT_TIMEOUT_MS}ms timeout; starting new observer without waiting",
                )
            }
        }

        val newJob =
            scope.launch(context) {
                when (mode) {
                    ObservationMode.LATEST -> flow.collectLatest { collector(it) }
                    ObservationMode.COMPLETE -> flow.collect { collector(it) }
                }
            }
        _currentJob = newJob
        return newJob
    }
}
