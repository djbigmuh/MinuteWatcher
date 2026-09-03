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
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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
        override val key: String = "widget_observation"

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private var lastSnapshotHash: Int? = null
    private var consecutiveUpdateFailures = 0
    private val observationRestartCount =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val stateMutex = Mutex()

    private val isObservationStarted = AtomicBoolean(false)
    private val observationScope = FlowObservationScope<GlobalSnapshot>(scope, "UpdateCoordinator")
    private val monitoringJob = AtomicReference<Job?>(null)

    init {
        ensureObservationStarted()
    }

        /**
         * Recovers widget observation state.
         *
         * Safe to call from any coroutine context.
         */
        override suspend fun recover() {
            monitoringJob.get()?.cancel()
            observationRestartCount.set(0)
            stateMutex.withLock {
                lastSnapshotHash = null
                consecutiveUpdateFailures = 0
                _status.value = TrackingStatus.Idle
            }
            isObservationStarted.set(false)
            ensureObservationStarted()
        }

        private fun ensureObservationStarted() {
            if (isObservationStarted.get()) return
            scope.launch {
                if (isObservationStarted.compareAndSet(false, true)) {
                    try {
                        setupObservation()
                    } catch (e: CancellationException) {
                        isObservationStarted.set(false)
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("UpdateCoordinator: Failed to setup observation", e)
                        isObservationStarted.set(false)
                    }
                }
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun setupObservation() {
            val currentJob = currentCoroutineContext()[Job]
            monitoringJob.get()?.takeIf { it !== currentJob }?.cancel()

            val handler =
                CoroutineExceptionHandler { _, cause ->
                    AppLogger.e("UpdateCoordinator: observation coroutine failed", cause)
                }

            val flow =
                usageStateRepository
                    .observeSnapshot()
                    .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)

            val newJob =
                observationScope.observe(flow, handler) { snapshot ->
                    try {
                        processSnapshot(snapshot)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("UpdateCoordinator: Failed to process snapshot in observation loop", e)
                    }
                }

            launchMonitoringJob(newJob)
        }

    private fun launchMonitoringJob(newJob: Job) {
        // Мониторинг завершения: если наблюдение упало — перезапускаем с экспоненциальной задержкой.
        val newMonitoringJob =
            scope.launch {
                newJob.join()
                if (observationScope.getJob() !== newJob) return@launch
                if (!isObservationStarted.get()) return@launch

                // Job finished while we expected it to be running.
                // Reset flag to allow ensureObservationStarted() to proceed.
                isObservationStarted.set(false)

                val restartCount = observationRestartCount.incrementAndGet()
                if (restartCount >= MAX_OBSERVATION_RESTARTS) {
                    AppLogger.e("UpdateCoordinator: observation restart limit reached ($restartCount)")
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                            "Widget observation failed permanently after $restartCount restarts",
                        )
                    return@launch
                }

                val delayMs = calculateRestartDelay(restartCount)
                AppLogger.w(
                    "UpdateCoordinator: observation terminated unexpectedly, " +
                        "scheduling restart #$restartCount in ${delayMs}ms",
                )
                kotlinx.coroutines.delay(delayMs.milliseconds)

                if (observationScope.getJob() === newJob) {
                    stateMutex.withLock {
                        lastSnapshotHash = null
                        consecutiveUpdateFailures = 0
                        _status.value = TrackingStatus.Idle
                    }
                    ensureObservationStarted()
                }
            }
        monitoringJob.set(newMonitoringJob)
    }

    private fun calculateRestartDelay(restartCount: Int): Long {
        val exponent = (restartCount - 1).coerceAtMost(MAX_RESTART_DELAY_EXPONENT)
        return (INITIAL_RESTART_DELAY_MS * (1 shl exponent)).coerceAtMost(MAX_RESTART_DELAY_MS)
    }

    @Suppress("ReturnCount")
    private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
        currentCoroutineContext().ensureActive()

        if (snapshot.suppressWidgetUpdate) {
            return
        }

        val newHash = SnapshotUtils.calculateContentHash(snapshot)

        val shouldUpdate = checkShouldUpdate(newHash)
        if (!shouldUpdate) return

        // Обновление виджета при отсутствии активных SIM выполняется
        // намеренно — виджет должен корректно отобразить состояние
        // "No SIM" или "No Data".
        val result = performWidgetUpdate()
        handleUpdateResult(result, newHash)
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
            withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                widgetUpdater.updateAll()
            } ?: run {
                AppLogger.w(
                    "UpdateCoordinator: Widget update timed out after " +
                        "${Constants.Time.WIDGET_UPDATE_TIMEOUT_MS}ms",
                )
                WidgetUpdateResult.Failure(isRetryable = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("UpdateCoordinator: widget update failed in processSnapshot", e)
            val isRetryable = isRetryableException(e)
            val reason =
                when {
                    isRemoteViewsLimitException(e) -> WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED
                    !isRetryable -> WidgetUpdateResult.FailureReason.TERMINAL
                    else -> WidgetUpdateResult.FailureReason.TRANSIENT
                }
            WidgetUpdateResult.Failure(isRetryable = isRetryable, reason = reason)
        }

    private suspend fun handleUpdateResult(
        result: WidgetUpdateResult,
        newHash: Int,
    ) {
        stateMutex.withLock {
            if (result.isSuccess) {
                _status.value = TrackingStatus.Active
                observationRestartCount.set(0)
                consecutiveUpdateFailures = 0
                lastSnapshotHash = newHash
            } else if ((result is WidgetUpdateResult.Failure) && !result.wasCoalesced) {
                processFailureResult(result)
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
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.NOT_SUPPORTED,
                        "Widget size limit exceeded",
                    )
                lastSnapshotHash = null
                handleUpdateFailure()
            }

            WidgetUpdateResult.FailureReason.TERMINAL -> {
                // For terminal failures we reset hash to ensure the next data change
                // will trigger a fresh update attempt, bypassing the hash-match check.
                AppLogger.w("UpdateCoordinator: terminal failure encountered, resetting hash")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Terminal widget update failure",
                    )
                lastSnapshotHash = null
                handleUpdateFailure()
            }
        }
    }

    private fun isRetryableException(e: Exception): Boolean =
        when {
            isRemoteViewsLimitException(e) -> false
            e is SecurityException -> false
            e is IllegalArgumentException -> true
            e is IllegalStateException -> false
            else -> true
        }

    private fun isRemoteViewsLimitException(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is android.os.TransactionTooLargeException) return true
            current = current.cause
        }
        return false
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
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Widget updates suppressed after $consecutiveUpdateFailures failures",
                    )
            }
        }

        /**
         * Notifies the coordinator that data has changed, potentially triggering a widget update.
         *
         * @param updateWidget If true, a widget update will be initiated.
         * @param force If true, an immediate widget update is forced, bypassing hash checks.
         * Safe to call from any coroutine context.
         * @throws kotlinx.coroutines.CancellationException if the notification process is cancelled.
         */
        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ): Boolean {
            if (!updateWidget) return true

            ensureObservationStarted()

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
        stateMutex.withLock {
            if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                AppLogger.w(
                    "UpdateCoordinator: forced widget update suppressed due to too many failures " +
                        "($consecutiveUpdateFailures)",
                )
                return false
            }
        }

        return try {
            val result = widgetUpdater.updateAll()

            stateMutex.withLock {
                if (result.isSuccess) {
                    consecutiveUpdateFailures = 0
                    observationRestartCount.set(0)
                    lastSnapshotHash =
                        SnapshotUtils.calculateContentHash(usageStateRepository.observeSnapshot().value)
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
        private const val INITIAL_RESTART_DELAY_MS = 5000L
        private const val MAX_RESTART_DELAY_MS = 80_000L
    }
    }
