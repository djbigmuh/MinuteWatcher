package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FlowObservationScope
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdateCoordinator
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.TrackingStatusMachine
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
 * Единый механизм обновления.
 *
 * Координатор является Singleton и использует ApplicationScope, поэтому цикл
 * наблюдения за снимками состояния (observeSnapshot) живет в течение всего
 * времени работы приложения.
 */
@Singleton
@Suppress("TooManyFunctions")
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
         * Updates the coordinator status using canonical prioritized rules.
         */
        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            statusMachine.transition(newStatus, force)
        }

        private var lastSnapshotHash: Int? = null
        private var consecutiveUpdateFailures = 0
        private var observationRestartCount = 0
        private var degradedCycleCount = 0
        private val stateMutex = Mutex()

        private val observationScope = FlowObservationScope<GlobalSnapshot>(scope, "UpdateCoordinator")
        private val setupJob = AtomicReference<Job?>(null)
        private val monitoringJob = AtomicReference<Job?>(null)
        private val currentObservationJob = AtomicReference<Job?>(null)
        private val currentUpdateJob = AtomicReference<Job?>(null)

        init {
            ensureObservationStarted()
        }

        /**
         * Recovers widget observation state.
         *
         * Safe to call from any coroutine context.
         */
        override suspend fun recover() {
            AppLogger.d("UpdateCoordinator: recover() called, resetting all counters")

            // 1. Cancel and join any pending setup (initial SIM wait)
            setupJob.get()?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 2. Cancel and join current observation and monitoring
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                observationScope.getJob()?.cancelAndJoin()
            }
            observationScope.shutdown()

            monitoringJob.get()?.let { job ->
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    job.cancelAndJoin()
                } ?: job.cancel()
            }

            // 3. Reset state under mutex
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

            ensureObservationStarted()
        }

        private fun ensureObservationStarted() {
            // Check if already active
            val currentJob = observationScope.getJob()
            if (currentJob != null && currentJob.isActive) return

            // Setup already in progress?
            if (setupJob.get()?.isActive == true) return

            val newSetupJob =
                scope.launch {
                    try {
                        usageStateRepository.observeSimCardUiModels().first { it.isNotEmpty() }
                        AppLogger.d("UpdateCoordinator: initial SIM list received, starting observation")
                        setupObservation()
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
                    } finally {
                        setupJob.compareAndSet(coroutineContext.job, null)
                    }
                }

            setupJob.getAndSet(newSetupJob)?.cancel()
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

                stateMutex.withLock {
                    val newJob =
                        observationScope.observe(flow, handler) { snapshot ->
                            processSnapshotSafe(snapshot)
                        }
                    currentObservationJob.set(newJob)
                    monitoringJob.getAndSet(launchMonitoringJob(newJob))?.cancel()
                    transitionStatus(TrackingStatus.Active)
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
                    withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                        rawFlow.first()
                    }
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

    @Suppress("SwallowedException")
        private suspend fun processSnapshotSafe(snapshot: GlobalSnapshot) {
            try {
                processSnapshot(snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("SwallowedException")
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
                // We use CancellationException WITHOUT a non-cancellation cause to prevent crashing test scope.
                throw CancellationException("Observation loop failed: ${e.message}")
            }
        }

    private fun launchMonitoringJob(newJob: Job): Job {
            // Monitoring completion: if observation fails — restart with exponential delay.
            return scope.launch {
                newJob.join()

                // Atomically check if this job is still the current one under mutex
                val isCurrent =
                    stateMutex.withLock {
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

                // Re-check current job before initiating restart
                val shouldInitiateRestart =
                    stateMutex.withLock {
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
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Widget observation permanently stopped after $cycles degraded cycles",
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
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    "Widget observation failed after $restartCount restarts. " +
                        "Retrying in ${MAX_RESTART_COOLDOWN_MS / Constants.Time.MILLIS_IN_SECOND}s",
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

        @Suppress("ReturnCount")
        private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
            currentCoroutineContext().ensureActive()

            val newHash = SnapshotUtils.calculateContentHash(snapshot)

            val shouldUpdate = checkShouldUpdate(newHash)
            if (!shouldUpdate) return

            if (snapshot.suppressWidgetUpdate) {
                // Хэш НЕ обновляется: виджет не обновлён,
                // следующее изменение данных должно триггернуть обновление.
                return
            }

            // Оборачиваем обновление в coroutineScope, чтобы выделить отдельный Job для этого процесса.
            // Это позволяет внешним вызовам (performForcedUpdate) отменить текущее обновление,
            // не прерывая весь цикл наблюдения (collectLatest).
            coroutineScope {
                currentUpdateJob.set(coroutineContext.job)
                try {
                    val result = performWidgetUpdate()

                    // Проверяем, что снапшот все еще актуален.
                    ensureActive()

                    handleUpdateResult(result, newHash)

                    if (result is WidgetUpdateResult.Failure &&
                        result.reason == WidgetUpdateResult.FailureReason.TERMINAL
                    ) {
                        // Throwing CancellationException here will fail the current observation job
                        // and trigger the monitoring-based restart with backoffs.
                        throw CancellationException("Terminal widget update failure")
                    }
                } finally {
                    currentUpdateJob.compareAndSet(coroutineContext.job, null)
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
                        // Update hash of the latest successfully processed state.
                        lastSnapshotHash = newHash
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
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Widget updates suppressed after $consecutiveUpdateFailures failures",
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
         * @throws CancellationException if the notification process is cancelled.
         */
        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ): Boolean {
            if (!updateWidget) return true

            return if (force) {
                val success = performForcedUpdate()
                if (success) {
                    dataRefreshNotifier.notifyRefresh(isManual = true, suppressWidgetUpdate = true)
                }
                success
            } else {
                dataRefreshNotifier.notifyRefresh(isManual = false, suppressWidgetUpdate = false)
                // Реактивный путь в init обработает обновление, если данные изменились.
                // Мы возвращаем true, так как уведомление успешно отправлено.
                true
            }
        }

        private suspend fun performForcedUpdate(): Boolean {
            val suppressed =
                stateMutex.withLock {
                    consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES
                }
            if (suppressed) {
                AppLogger.w(
                    "UpdateCoordinator: forced widget update suppressed due to too many failures " +
                        "($consecutiveUpdateFailures)",
                )
                return false
            }

            // Отменяем текущее реактивное обновление, если оно идет.
            currentUpdateJob.getAndSet(null)?.cancel()

            // Reset updater state before forced update (e.g. to clear LimitExceededHandled flag)
            widgetUpdater.recover()

            return try {
                val result = widgetUpdater.updateAll()

                stateMutex.withLock {
                    if (result.isSuccess) {
                        consecutiveUpdateFailures = 0
                        observationRestartCount = 0
                        degradedCycleCount = 0

                        // Читаем актуальный снимок ПОСЛЕ завершения обновления,
                        // чтобы lastSnapshotHash соответствовал реальности.
                        val latestSnapshot = usageStateRepository.observeSnapshot().value
                        lastSnapshotHash = SnapshotUtils.calculateContentHash(latestSnapshot)
                        true
                    } else {
                        handleUpdateFailure()
                        false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("Force widget update failed, hash not updated", e)
                stateMutex.withLock {
                    handleUpdateFailure()
                }
                false
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
