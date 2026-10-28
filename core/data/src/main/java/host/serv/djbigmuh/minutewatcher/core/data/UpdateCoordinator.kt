package host.serv.djbigmuh.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.FlowObservationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.firstWithTimeoutOrLog
import host.serv.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdateCoordinator
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatusMachine
import host.serv.djbigmuh.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Centralized update mechanism.
 *
 * Coordinator is a Singleton and uses ApplicationScope, so the observation
 * cycle (observeSnapshot) lives for the entire process lifetime and is started explicitly via [start].
 */
@Singleton
@Suppress("LargeClass")
class UpdateCoordinator
    @Inject
    constructor(
        private val dataRefreshNotifier: DataRefreshNotifier,
        private val widgetUpdater: WidgetUpdater,
        private val usageStateRepository: UsageStateRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) : WidgetUpdateCoordinator {
        override val key: TrackingKey = TrackingKey.WIDGET_OBSERVATION

        private val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        /**
         * Private exception type used to fail the observation loop on terminal errors.
         * This type does NOT extend [CancellationException], so it will fail the [Job]
         * and trigger the monitoring restart mechanism.
         */
        private class TerminalUpdateException(
            message: String,
        ) : RuntimeException(message)

        /**
         * Updates the coordinator status using canonical prioritized rules.
         */
        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            statusMachine.transition(newStatus, force)
        }

        /**
         * Hash of the latest snapshot content confirmed to be rendered on the widget.
         *
         * Invariant: [lastSnapshotHash] corresponds to content no newer than what was actually
         * rendered. Coalesced updates do not update this hash because a joined execution
         * cannot guarantee that caller's snapshot content was rendered. Any discrepancy is
         * compensated by the subsequent reactive cycle.
         */
        private var lastSnapshotHash: Int? = null
        private var consecutiveUpdateFailures = 0
        private var observationRestartCount = 0
        private var degradedCycleCount = 0

        @VisibleForTesting
        internal var observationGeneration = 0L
        private val stateMutex = Mutex()

        private val observationScope = FlowObservationScope<GlobalSnapshot>(scope, "UpdateCoordinator")
        private val setupJob = AtomicReference<Job?>(null)
        private val monitoringJob = AtomicReference<Job?>(null)

        @VisibleForTesting
        internal val currentObservationJob = AtomicReference<Job?>(null)
        private val currentUpdateJob = AtomicReference<Job?>(null)

        @VisibleForTesting
        internal val takeoverUpdateJob = AtomicReference<Job?>(null)

        /**
         * Explicitly starts observation from [Application.onCreate].
         * Constructor has no side effects. Method is idempotent and thread-safe; repeated calls are ignored.
         */
        fun start() {
            ensureObservationStarted()
        }

        private suspend fun reconcileObservationState() =
            stateMutex.withLock {
                reconcileObservationStateLocked()
            }

        private fun reconcileObservationStateLocked() {
            val setup = setupJob.get()
            if (setup != null && setup.isActive) {
                return
            }
            val currentJob = observationScope.currentJob
            if ((currentJob != null) && currentJob.isActive && (currentObservationJob.get() !== currentJob)) {
                currentObservationJob.set(currentJob)
                observationGeneration++
                val currentGen = observationGeneration
                val previousMonitoring = monitoringJob.getAndSet(null)
                previousMonitoring?.cancel()
                val newMonitoringJob = launchMonitoringJob(currentJob, currentGen)
                if (!monitoringJob.compareAndSet(null, newMonitoringJob)) {
                    newMonitoringJob.cancel()
                }
                transitionStatus(TrackingStatus.Active)
            }
        }

        private fun tryReconcileObservationState() {
            if (stateMutex.tryLock()) {
                try {
                    reconcileObservationStateLocked()
                } finally {
                    stateMutex.unlock()
                }
            }
        }

        /**
         * Recovers widget observation state.
         *
         * Safe to call from any coroutine context.
         */
        override suspend fun recover() {
            AppLogger.d("UpdateCoordinator: recover() called, resetting all counters")

            // 1. Cancel and join pending setup (initial SIM wait) first
            setupJob.get()?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 2. Cancel and join monitoring job
            monitoringJob.get()?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 3. Cancel and join current observation
            observationScope.currentJob?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 4. Reset state under mutex
            val stateResetSuccess =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    stateMutex.withLock {
                        observationRestartCount = 0
                        degradedCycleCount = 0
                        lastSnapshotHash = null
                        consecutiveUpdateFailures = 0
                        transitionStatus(TrackingStatus.Idle, force = true)
                    }
                    true
                } ?: false

            if (!stateResetSuccess) {
                AppLogger.e("UpdateCoordinator: recovery blocked by stateMutex")
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Recovery blocked: state lock stuck",
                    ),
                )
            }

            widgetUpdater.recover()

            reconcileObservationState()

            ensureObservationStarted()
        }

        private fun ensureObservationStarted() {
            synchronized(this) {
                tryReconcileObservationState()

                // Check if already active
                val currentJob = observationScope.currentJob
                if ((currentJob != null) && currentJob.isActive) return@synchronized

                // Setup already in progress?
                val activeSetup = setupJob.get()
                if ((activeSetup != null) && activeSetup.isActive) return@synchronized

                val previousSetup = setupJob.getAndSet(null)
                previousSetup?.cancel()

                val newSetupJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            val success =
                                withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                                    usageStateRepository.observeSimCardUiModels().first { it.isNotEmpty() }
                                    true
                                } ?: false

                            if (success) {
                                AppLogger.d("UpdateCoordinator: initial SIM list received, starting observation")
                                setupObservation()
                            } else {
                                AppLogger.w("UpdateCoordinator: Timeout waiting for initial SIMs, starting with empty")
                                setupObservation()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("UpdateCoordinator: Failed to setup observation", e)
                            transitionStatus(
                                TrackingStatus.Error(
                                    TrackingStatus.ErrorType.UNKNOWN,
                                    "Failed to start widget observation: ${e.message}",
                                ),
                            )
                        }
                    }

                if (setupJob.compareAndSet(null, newSetupJob)) {
                    newSetupJob.invokeOnCompletion {
                        setupJob.compareAndSet(newSetupJob, null)
                    }
                    newSetupJob.start()
                } else {
                    newSetupJob.cancel()
                }
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun setupObservation() {
            val handler =
                CoroutineExceptionHandler { _, cause ->
                    AppLogger.e("UpdateCoordinator: observation coroutine failed", cause)
                }

            try {
                val firstSnapshot = waitForInitialSnapshot()

                if (firstSnapshot == null) {
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Widget observation failed to start",
                        ),
                    )
                    return
                }

                val flow =
                    usageStateRepository
                        .observeSnapshot()
                        .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                        .debounce { snapshot ->
                            if (snapshot.isManualUpdate) 0L else Constants.Widget.DEBOUNCE_MS
                        }

                var newJob: Job? = null
                try {
                    stateMutex.withLock {
                        val job =
                            observationScope.observe(flow, handler) { snapshot ->
                                processSnapshotSafe(snapshot)
                            }
                        newJob = job
                        currentObservationJob.set(job)
                        observationGeneration++
                        val currentGen = observationGeneration
                        val previousMonitoring = monitoringJob.getAndSet(null)
                        previousMonitoring?.cancel()
                        val newMonitoringJob = launchMonitoringJob(job, currentGen)
                        if (!monitoringJob.compareAndSet(null, newMonitoringJob)) {
                            newMonitoringJob.cancel()
                        }
                        transitionStatus(TrackingStatus.Active)
                    }
                } catch (e: CancellationException) {
                    newJob?.cancel()
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("UpdateCoordinator: Failed to setup observation", e)
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Failed to start widget observation: ${e.message}",
                    ),
                    force = true,
                )
            }
        }

        private suspend fun waitForInitialSnapshot(): GlobalSnapshot? {
            // Ensure flow produces at least one snapshot within timeout before starting monitoring.
            // This prevents "silent hangs" if the data source is corrupted or inactive.
            // We use a retry loop with exponential backoff to handle slow initial snapshots.
            var firstSnapshot: GlobalSnapshot? = null
            var attempt = 0
            var delayMs = Constants.Flow.RETRY_DELAY_MS

            while ((attempt < MAX_INITIAL_SNAPSHOT_ATTEMPTS) && (firstSnapshot == null)) {
                val rawFlow =
                    usageStateRepository
                        .observeSnapshot()
                        .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }

                firstSnapshot =
                    rawFlow.firstWithTimeoutOrLog(
                        timeoutMillis = Constants.Flow.LONG_TIMEOUT_MS,
                        tag = "UpdateCoordinator",
                        key = "initialSnapshot",
                    )
                if (firstSnapshot == null) {
                    attempt++
                    if (attempt < MAX_INITIAL_SNAPSHOT_ATTEMPTS) {
                        AppLogger.w(
                            "UpdateCoordinator: snapshot not ready, retry " +
                                "$attempt/$MAX_INITIAL_SNAPSHOT_ATTEMPTS",
                        )
                        delay(delayMs.milliseconds)
                        delayMs *= 2
                    }
                }
            }

            if (firstSnapshot == null) {
                AppLogger.e(
                    "UpdateCoordinator: Widget observation failed to start " +
                        "after $MAX_INITIAL_SNAPSHOT_ATTEMPTS attempts",
                )
            }
            return firstSnapshot
        }

        @Suppress("ThrowsCount")
        private suspend fun processSnapshotSafe(snapshot: GlobalSnapshot) {
            try {
                processSnapshot(snapshot)
            } catch (e: TerminalUpdateException) {
                // Terminal failure from within processSnapshot. Rethrow to fail observation job.
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (
                e: Exception,
            ) {
                AppLogger.e("UpdateCoordinator: Failed to process snapshot in observation loop", e)
                val newHash = runCatching { SnapshotUtils.calculateContentHash(snapshot) }.getOrDefault(0)
                handleUpdateResult(
                    WidgetUpdateResult.Failure(
                        isRetryable = false,
                        reason = WidgetUpdateResult.FailureReason.TERMINAL,
                    ),
                    newHash,
                )
                // Fail the observation loop to trigger monitoring-based restart.
                // Rethrowing original exception instead of wrapping in CancellationException
                // to ensure the observation Job fails correctly.
                throw e
            }
        }

        private fun launchMonitoringJob(
            newJob: Job,
            generation: Long = observationGeneration,
        ): Job {
            /**
             * Monitoring completion: if observation job fails (due to terminal error or unexpected exception)
             * this monitoring job will perform a restart with exponential delay.
             *
             * Invariant: Generation counter and observation job identity ([generation] + [newJob])
             * form the sole criterion for currency, ensuring independence from registration order.
             *
             * Note: external cancellations (takeover, recover, shutdown) are handled via join()
             * and ensureActive() to avoid restarting when deliberately stopped.
             */
            return scope.launch {
                newJob.join()

                currentCoroutineContext().ensureActive()

                // Atomically check if this job is still current using generation and job identity
                val isCurrent =
                    stateMutex.withLock {
                        observationGeneration == generation &&
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

                // Re-check current generation and job before initiating restart
                val shouldInitiateRestart =
                    stateMutex.withLock {
                        observationGeneration == generation &&
                            currentObservationJob.get() === newJob
                    }

                if (shouldInitiateRestart) {
                    stateMutex.withLock {
                        lastSnapshotHash = null
                        consecutiveUpdateFailures = 0
                        // In degraded mode keep Error status for UI info
                        if (restartCount < MAX_OBSERVATION_RESTARTS) {
                            transitionStatus(TrackingStatus.Idle)
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
                    force = true,
                )
                return -1 // Signal to stop
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
                force = true,
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

        private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
            currentCoroutineContext().ensureActive()

            val newHash = SnapshotUtils.calculateContentHash(snapshot)

            val shouldUpdate = checkShouldUpdate(newHash)
            if (!shouldUpdate) return

            if (snapshot.suppressWidgetUpdate) {
                // Hash is NOT updated: widget is not updated, next data change should trigger update.
                return
            }

            // Wrap update in coroutineScope to allocate a separate Job for this process.
            // This allows external calls (performForcedUpdate) to cancel current update
            // without interrupting the whole observation cycle.
            coroutineScope {
                val updateJob = currentCoroutineContext().job
                currentUpdateJob.set(updateJob)
                try {
                    val result = performWidgetUpdate()

                    // Check that the snapshot is still relevant.
                    ensureActive()

                    handleUpdateResult(result, newHash)

                    if (result is WidgetUpdateResult.Failure &&
                        result.reason == WidgetUpdateResult.FailureReason.TERMINAL
                    ) {
                        // Throwing non-cancellation exception here will fail the current observation job
                        // and trigger the monitoring-based restart with backoffs.
                        throw TerminalUpdateException("Terminal widget update failure")
                    }
                } catch (e: CancellationException) {
                    val isTakeover = takeoverUpdateJob.compareAndSet(updateJob, null)
                    if (isTakeover) {
                        AppLogger.d(
                            "UpdateCoordinator: reactive update superseded by forced update, " +
                                "finishing iteration",
                        )
                    } else {
                        throw e
                    }
                } finally {
                    currentUpdateJob.compareAndSet(updateJob, null)
                }
            }
        }

        private suspend fun checkShouldUpdate(newHash: Int): Boolean =
            stateMutex.withLock {
                if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                    AppLogger.d(
                        "UpdateCoordinator: widget update suppressed due to too many failures " +
                            "($consecutiveUpdateFailures)",
                    )
                    return@withLock false
                }

                if (newHash == lastSnapshotHash) {
                    AppLogger.d("UpdateCoordinator: skipping reactive update, hash match ($newHash)")
                    return@withLock false
                }
                true
            }

        private suspend fun performWidgetUpdate(): WidgetUpdateResult =
            try {
                widgetUpdater.updateAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("UpdateCoordinator: unexpected widget update failure", e)
                WidgetUpdateResult.Failure(
                    isRetryable = false,
                    reason = WidgetUpdateResult.FailureReason.TERMINAL,
                )
            }

        private suspend fun handleUpdateResult(
            result: WidgetUpdateResult,
            newHash: Int,
        ) {
            stateMutex.withLock {
                when (result) {
                    is WidgetUpdateResult.Success -> {
                        transitionStatus(TrackingStatus.Active, force = true)
                        observationRestartCount = 0
                        degradedCycleCount = 0
                        consecutiveUpdateFailures = 0
                        // Update hash only for non-coalesced success.
                        if (!result.wasCoalesced) {
                            lastSnapshotHash = newHash
                        }
                    }

                    is WidgetUpdateResult.Failure -> {
                        if (!result.wasCoalesced) {
                            processFailureResult(result)
                        }
                    }
                }
            }
        }

        private fun processFailureResult(result: WidgetUpdateResult.Failure) {
            when (result.reason) {
                WidgetUpdateResult.FailureReason.TRANSIENT -> {
                    handleUpdateFailure()
                }

                WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED -> {
                    AppLogger.e(
                        "UpdateCoordinator: terminal limit exceeded (TransactionTooLarge), " +
                            "resetting hash to force retry on next data change",
                    )
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.NOT_SUPPORTED,
                            "Widget size limit exceeded",
                            code = TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT,
                        ),
                    )
                    lastSnapshotHash = null
                }

                WidgetUpdateResult.FailureReason.TERMINAL -> {
                    // For terminal failures we reset hash to ensure the next data change
                    // will trigger a fresh update attempt, bypassing the hash-match check.
                    AppLogger.w("UpdateCoordinator: terminal failure encountered, resetting hash")
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Terminal widget update failure",
                        ),
                    )
                    lastSnapshotHash = null
                }
            }
        }

        private fun handleUpdateFailure() {
            consecutiveUpdateFailures++
            AppLogger.w(
                "UpdateCoordinator: widget update failed ($consecutiveUpdateFailures), " +
                    "hash not updated",
            )
            if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                AppLogger.e(
                    "UpdateCoordinator: reached max consecutive failures ($consecutiveUpdateFailures). " +
                        "Updates suppressed.",
                )
                transitionStatus(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Widget updates suppressed after $consecutiveUpdateFailures failures",
                        code = TrackingStatus.ErrorCode.RENDER_FAILURE,
                    ),
                )
            }
        }

        /**
         * Notifies the coordinator that data has changed, potentially triggering a widget update.
         *
         * @param updateWidget If true, a widget update will be initiated.
         * @param force If true, an immediate widget update is forced, bypassing hash checks.
         * Safe to call from any coroutine context.
         * @throws CancellationException if the notification process is canceled.
         */
        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ): Boolean {
            if (!updateWidget) return true

            return if (force) {
                val outcome = performForcedUpdate()
                if (outcome.success) {
                    dataRefreshNotifier.notifyRefresh(
                        isManual = true,
                        // Suppress reactive update ONLY if data DID NOT change during render.
                        // If it did — let the reactive cycle immediately finish updating the widget.
                        suppressWidgetUpdate = !outcome.contentChangedDuringUpdate,
                    )
                }
                outcome.success
            } else {
                dataRefreshNotifier.notifyRefresh(isManual = false, suppressWidgetUpdate = false)
                // Reactive path in init will handle the update if data changed.
                // We return true as the notification was successfully sent.
                true
            }
        }

        private data class ForcedUpdateOutcome(
            val success: Boolean,
            val contentChangedDuringUpdate: Boolean,
        )

        private suspend fun performForcedUpdate(): ForcedUpdateOutcome {
            val suppressed =
                stateMutex.withLock {
                    consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES
                }
            if (suppressed) {
                AppLogger.w(
                    "UpdateCoordinator: forced widget update suppressed due to too many failures " +
                        "($consecutiveUpdateFailures)",
                )
                return ForcedUpdateOutcome(false, contentChangedDuringUpdate = false)
            }

            // Cancel current reactive update if it's in progress.
            val oldJob = currentUpdateJob.getAndSet(null)
            if (oldJob != null) {
                takeoverUpdateJob.set(oldJob)
                oldJob.cancel()
                // Wait for the canceled job to finish to avoid coalescing in WidgetUpdater
                withContext(NonCancellable) {
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        oldJob.join()
                    }
                }
                takeoverUpdateJob.compareAndSet(oldJob, null)
            }

            // Reset updater state before forced update (e.g. to clear LimitExceededHandled flag)
            widgetUpdater.recover()

            return try {
                // Snapshot that is guaranteed to be not newer than what the renderer will see.
                val snapshotBefore = usageStateRepository.observeSnapshot().value
                val hashBefore = SnapshotUtils.calculateContentHash(snapshotBefore)

                val result = widgetUpdater.updateAll()

                val hashAfter = SnapshotUtils.calculateContentHash(usageStateRepository.observeSnapshot().value)

                stateMutex.withLock {
                    when (result) {
                        is WidgetUpdateResult.Success -> processForcedSuccessResult(result, hashBefore, hashAfter)
                        is WidgetUpdateResult.Failure -> {
                            processFailureResult(result)
                            ForcedUpdateOutcome(success = false, contentChangedDuringUpdate = false)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(
                    "UpdateCoordinator: forced widget update failed with unexpected exception, " +
                        "treating as terminal failure",
                    e,
                )
                stateMutex.withLock {
                    processFailureResult(
                        WidgetUpdateResult.Failure(
                            reason = WidgetUpdateResult.FailureReason.TERMINAL,
                        ),
                    )
                }
                ForcedUpdateOutcome(false, contentChangedDuringUpdate = false)
            }
        }

        private fun processForcedSuccessResult(
            result: WidgetUpdateResult.Success,
            hashBefore: Int,
            hashAfter: Int,
        ): ForcedUpdateOutcome {
            consecutiveUpdateFailures = 0
            observationRestartCount = 0
            degradedCycleCount = 0

            return if (result.wasCoalesced) {
                ForcedUpdateOutcome(
                    success = true,
                    contentChangedDuringUpdate = true,
                )
            } else {
                lastSnapshotHash = hashBefore
                ForcedUpdateOutcome(
                    success = true,
                    contentChangedDuringUpdate = hashAfter != hashBefore,
                )
            }
        }

        private companion object {
            private const val MAX_RESTART_DELAY_EXPONENT = 4
            private const val MAX_OBSERVATION_RESTARTS = 5
            private const val MAX_DEGRADED_CYCLES = 3
            private const val INITIAL_RESTART_DELAY_MS = 5000L
            private const val MAX_RESTART_DELAY_MS = 80_000L
            private const val MAX_RESTART_COOLDOWN_MS = 300_000L
            private const val MAX_INITIAL_SNAPSHOT_ATTEMPTS = 3
        }
    }
