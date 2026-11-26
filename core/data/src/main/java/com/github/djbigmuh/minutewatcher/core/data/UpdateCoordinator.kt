package com.github.djbigmuh.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.github.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.FlowObservationScope
import com.github.djbigmuh.minutewatcher.core.common.util.firstWithTimeoutOrLog
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateCoordinator
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.TrackingKey
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatusMachine
import com.github.djbigmuh.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
 *
 * Single-owner reactive lifecycle state machine: observation jobs, generations and
 * update/takeover slots are mutated together under one ownership and one mutex.
 */
@OptIn(FlowPreview::class)
@Singleton
class UpdateCoordinator
    @Inject
    constructor(
        private val dataRefreshNotifier: DataRefreshNotifier,
        private val widgetUpdater: WidgetUpdater,
        private val usageStateRepository: UsageStateRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository,
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

        @VisibleForTesting
        internal val setupJob = AtomicReference<Job?>(null)
        private val monitoringJob = AtomicReference<Job?>(null)

        @VisibleForTesting
        internal val currentObservationJob = AtomicReference<Job?>(null)
        private val currentUpdateJob = AtomicReference<Job?>(null)

        @VisibleForTesting
        internal val takeoverUpdateJob get() = forcedUpdateFlow.takeoverUpdateJob

        private val settingsChangeObserver =
            SettingsChangeObserver(
                scope = scope,
                appSettingsRepository = appSettingsRepository,
                simWidgetVisibilityRepository = simWidgetVisibilityRepository,
                onSettingsChanged = { handleSettingsChanged() },
            )

        private val restartScheduler =
            ObservationRestartScheduler(
                scope = scope,
                stateMutex = stateMutex,
                currentObservationJob = currentObservationJob,
                getObservationGeneration = { observationGeneration },
                transitionStatus = { status, force -> transitionStatus(status, force) },
                ensureObservationStarted = { ensureObservationStarted() },
                getObservationRestartCount = { observationRestartCount },
                setObservationRestartCount = { observationRestartCount = it },
                getDegradedCycleCount = { degradedCycleCount },
                setDegradedCycleCount = { degradedCycleCount = it },
                resetRestartState = {
                    lastSnapshotHash = null
                    consecutiveUpdateFailures = 0
                },
            )

        private val forcedUpdateFlow =
            ForcedUpdateFlow(
                stateMutex = stateMutex,
                currentUpdateJob = currentUpdateJob,
                takeoverUpdateJob = AtomicReference(null),
                widgetUpdater = widgetUpdater,
                usageStateRepository = usageStateRepository,
                getConsecutiveUpdateFailures = { consecutiveUpdateFailures },
                setConsecutiveUpdateFailures = { consecutiveUpdateFailures = it },
                processFailureResult = { failure -> processFailureResult(failure) },
                processSuccessResult = { result, hashBefore, hashAfter ->
                    consecutiveUpdateFailures = 0
                    observationRestartCount = 0
                    degradedCycleCount = 0
                    if (result.wasCoalesced) {
                        ForcedUpdateOutcome(success = true, contentChangedDuringUpdate = true)
                    } else {
                        lastSnapshotHash = hashBefore
                        ForcedUpdateOutcome(success = true, contentChangedDuringUpdate = hashAfter != hashBefore)
                    }
                },
            )

        /**
         * Explicitly starts observation from [android.app.Application.onCreate].
         * Constructor has no side effects. Method is idempotent and thread-safe; repeated calls are ignored.
         */
        fun start() {
            ensureObservationStarted()
            settingsChangeObserver.start()
        }

        private suspend fun handleSettingsChanged() {
            stateMutex.withLock {
                lastSnapshotHash = null
            }
            notifyDataChanged(updateWidget = true, force = true)
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
            val currentJob = currentObservationJob.get()
            if (currentJob != null && currentJob.isActive) {
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
            setupJob.getAndSet(null)?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 2. Cancel and join monitoring job
            monitoringJob.getAndSet(null)?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 3. Cancel and join current observation
            currentObservationJob.getAndSet(null)?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 4. Restart settings observers
            settingsChangeObserver.restart()

            // 5. Reset state under mutex
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

                val currentJob = currentObservationJob.get()
                if ((currentJob != null) && currentJob.isActive) return@synchronized

                val activeSetup = setupJob.get()
                if ((activeSetup != null) && activeSetup.isActive) return@synchronized

                val existingSetup = setupJob.get()
                if (existingSetup != null && existingSetup.isActive) return@synchronized

                val previousSetup = setupJob.getAndSet(null)
                previousSetup?.cancel()

                val newSetupJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        @Suppress("TooGenericExceptionCaught")
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
                            // Operation must complete without propagating exception for observation setup.
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

        private suspend fun setupObservation() {
            val handler =
                CoroutineExceptionHandler { _, cause ->
                    AppLogger.e("UpdateCoordinator: observation coroutine failed", cause)
                }

            @Suppress("TooGenericExceptionCaught")
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

                val snapshotFlow =
                    usageStateRepository
                        .observeSnapshot()
                        .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                        .debounce { snapshot ->
                            if (snapshot.isManualUpdate) {
                                0L.milliseconds
                            } else {
                                Constants.Widget.DEBOUNCE_MS.milliseconds
                            }
                        }

                val job =
                    observationScope.observe(snapshotFlow, handler) { snapshot ->
                        processSnapshotChange(snapshot)
                    }

                stateMutex.withLock {
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
                throw e
            } catch (e: Exception) {
                // Operation must complete without propagating exception for setup observation.
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
            // Try to get the first snapshot immediately without timeout
            val firstSnapshot = usageStateRepository.observeSnapshot().first()

            if (firstSnapshot.timestamp > Constants.Data.EMPTY_TIMESTAMP) {
                return firstSnapshot
            }

            // If timestamp is empty, wait for a valid one with timeout
            var attempt = 0
            var delayMs = Constants.Flow.RETRY_DELAY_MS

            while (attempt < MAX_INITIAL_SNAPSHOT_ATTEMPTS) {
                val snapshot =
                    usageStateRepository
                        .observeSnapshot()
                        .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                        .firstWithTimeoutOrLog(
                            timeoutMillis = Constants.Flow.LONG_TIMEOUT_MS,
                            tag = "UpdateCoordinator",
                            key = "initialSnapshot",
                        )

                if (snapshot != null) {
                    return snapshot
                }

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

            AppLogger.e(
                "UpdateCoordinator: Widget observation failed to start " +
                    "after $MAX_INITIAL_SNAPSHOT_ATTEMPTS attempts",
            )
            return null
        }

        private fun launchMonitoringJob(
            newJob: Job,
            generation: Long = observationGeneration,
        ): Job = restartScheduler.launchMonitoringJob(newJob, generation)

        @Suppress("ThrowsCount", "TooGenericExceptionCaught")
        private suspend fun processSnapshotChange(snapshot: GlobalSnapshot) {
            currentCoroutineContext().ensureActive()
            val newHash = SnapshotUtils.calculateContentHash(snapshot)
            val shouldUpdate = checkShouldUpdate(newHash)
            if (!shouldUpdate) return

            coroutineScope {
                val updateJob = currentCoroutineContext().job
                currentUpdateJob.set(updateJob)
                try {
                    val result = performWidgetUpdate()

                    ensureActive()

                    handleUpdateResult(result, newHash)

                    if (result is WidgetUpdateResult.Failure &&
                        result.reason == WidgetUpdateResult.FailureReason.TERMINAL
                    ) {
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
                } catch (e: TerminalUpdateException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("UpdateCoordinator: Exception during widget update", e)
                    handleUpdateResult(
                        WidgetUpdateResult.Failure(
                            isRetryable = false,
                            reason = WidgetUpdateResult.FailureReason.TERMINAL,
                        ),
                        newHash,
                    )
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
                    println("TRACE_CHECK: hash match, newHash=$newHash, lastHash=$lastSnapshotHash")
                    AppLogger.d("UpdateCoordinator: skipping reactive update, hash match ($newHash)")
                    return@withLock false
                }
                println("TRACE_CHECK: should update, newHash=$newHash")
                true
            }

        private suspend fun performWidgetUpdate(): WidgetUpdateResult =
            @Suppress("TooGenericExceptionCaught")
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
                    AppLogger.w("UpdateCoordinator: terminal failure encountered, resetting hash")
                    transitionStatus(
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Terminal widget update failure",
                            code = TrackingStatus.ErrorCode.RENDER_FAILURE,
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
        ): Boolean =
            !updateWidget ||
                if (force) {
                    val outcome = performForcedUpdate()
                    if (outcome.success) {
                        dataRefreshNotifier.notifyRefresh(
                            isManual = true,
                            suppressWidgetUpdate = false,
                        )
                    }
                    outcome.success
                } else {
                    dataRefreshNotifier.notifyRefresh(isManual = false, suppressWidgetUpdate = false)
                    true
                }

        private suspend fun performForcedUpdate(): ForcedUpdateOutcome = forcedUpdateFlow.performForcedUpdate()

        private companion object {
            private const val MAX_INITIAL_SNAPSHOT_ATTEMPTS = 3
        }
    }
