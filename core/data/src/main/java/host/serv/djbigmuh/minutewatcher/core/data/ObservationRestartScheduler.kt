package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Schedules observation restarts with exponential backoff and handles degraded mode.
 */
internal class ObservationRestartScheduler(
    private val scope: CoroutineScope,
    private val stateMutex: Mutex,
    private val currentObservationJob: AtomicReference<Job?>,
    private val getObservationGeneration: () -> Long,
    private val transitionStatus: suspend (TrackingStatus, Boolean) -> Unit,
    private val ensureObservationStarted: suspend () -> Unit,
    private val getObservationRestartCount: () -> Int,
    private val setObservationRestartCount: (Int) -> Unit,
    private val getDegradedCycleCount: () -> Int,
    private val setDegradedCycleCount: (Int) -> Unit,
    private val resetRestartState: suspend () -> Unit,
) {
    var observationRestartCount: Int
        get() = getObservationRestartCount()
        set(value) = setObservationRestartCount(value)

    var degradedCycleCount: Int
        get() = getDegradedCycleCount()
        set(value) = setDegradedCycleCount(value)

    fun launchMonitoringJob(
        newJob: Job,
        generation: Long = getObservationGeneration(),
    ): Job {
        return scope.launch {
            newJob.join()

            currentCoroutineContext().ensureActive()

            val isCurrent =
                stateMutex.withLock {
                    getObservationGeneration() == generation &&
                        currentObservationJob.get() === newJob
                }
            if (!isCurrent) return@launch

            val (restartCount, cycles, isEnteringDegraded) =
                stateMutex.withLock {
                    val count = ++observationRestartCount
                    var currentCycles = degradedCycleCount
                    var enteringDegraded = false
                    if (count >= MAX_OBSERVATION_RESTARTS) {
                        currentCycles = ++degradedCycleCount
                        enteringDegraded = true
                    }
                    Triple(count, currentCycles, enteringDegraded)
                }

            val delayMs =
                if (isEnteringDegraded) {
                    handleDegradedMode(restartCount, cycles)
                } else {
                    calculateRestartDelay(restartCount)
                }

            if (delayMs < 0) return@launch // Permanent stop

            AppLogger.w(
                "UpdateCoordinator: observation terminated unexpectedly, " +
                    "scheduling restart #$restartCount in ${delayMs}ms",
            )
            delay(delayMs.milliseconds)

            val shouldInitiateRestart =
                stateMutex.withLock {
                    getObservationGeneration() == generation &&
                        currentObservationJob.get() === newJob
                }

            if (shouldInitiateRestart) {
                stateMutex.withLock {
                    resetRestartState()
                    if (restartCount < MAX_OBSERVATION_RESTARTS) {
                        transitionStatus(TrackingStatus.Idle, false)
                    }
                }
                ensureObservationStarted()
            }
        }
    }

    private suspend fun handleDegradedMode(
        restartCount: Int,
        cycles: Int,
    ): Long {
        if (cycles >= MAX_DEGRADED_CYCLES) {
            AppLogger.e(
                "UpdateCoordinator: observation restart limit reached ($restartCount) " +
                    "and max degraded cycles reached ($cycles). Permanent stop.",
            )
            transitionStatus(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Widget observation permanently stopped after $cycles degraded cycles",
                    code = TrackingStatus.ErrorCode.RENDER_FAILURE,
                ),
                true,
            )
            return -1
        }

        AppLogger.e(
            "UpdateCoordinator: observation restart limit reached ($restartCount), " +
                "entering degraded mode (cycle $cycles)",
        )
        transitionStatus(
            TrackingStatus.Error(
                TrackingStatus.ErrorType.UNKNOWN,
                "Widget observation failed after $restartCount restarts. " +
                    "Retrying in ${MAX_RESTART_COOLDOWN_MS / Constants.Time.MILLIS_IN_SECOND}s",
                code = TrackingStatus.ErrorCode.RENDER_FAILURE,
            ),
            true,
        )
        stateMutex.withLock {
            observationRestartCount = 0
        }
        return MAX_RESTART_COOLDOWN_MS
    }

    private fun calculateRestartDelay(restartCount: Int): Long {
        val exponent = (restartCount - 1).coerceAtMost(MAX_RESTART_DELAY_EXPONENT)
        return (INITIAL_RESTART_DELAY_MS * (1 shl exponent)).coerceAtMost(MAX_RESTART_DELAY_MS)
    }

    companion object {
        private const val MAX_RESTART_DELAY_EXPONENT = 4
        private const val MAX_OBSERVATION_RESTARTS = 5
        private const val MAX_DEGRADED_CYCLES = 3
        private const val INITIAL_RESTART_DELAY_MS = 5000L
        private const val MAX_RESTART_DELAY_MS = 80_000L
        private const val MAX_RESTART_COOLDOWN_MS = 300_000L
    }
}
