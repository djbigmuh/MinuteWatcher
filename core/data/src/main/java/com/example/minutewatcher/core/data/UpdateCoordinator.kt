package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.cancelAndJoinWithTimeout
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
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
    ) : DataChangeNotifier,
        TrackingStatusProvider {
        override val key: String = "widget_observation"

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private var lastSnapshotHash: Int? = null
    private var consecutiveUpdateFailures = 0
    private val observationRestartCount =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val widgetUpdateMutex = Mutex()
    private val initMutex = Mutex()

    private val isObservationStarted = AtomicBoolean(false)
    private val observationJob = AtomicReference<Job?>(null)
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
            if (!isObservationStarted.get()) {
                ensureObservationStarted()
            } else {
                restartObservation()
            }
        }

        private fun ensureObservationStarted() {
            if (isObservationStarted.get()) return
            scope.launch {
                if (isObservationStarted.compareAndSet(false, true)) {
                    setupObservation()
                }
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun setupObservation() {
            val currentJob = currentCoroutineContext()[Job]
            initMutex.withLock {
                cleanupOldObservation(currentJob)

                val handler =
                    CoroutineExceptionHandler { _, cause ->
                        AppLogger.e("UpdateCoordinator: observation coroutine failed", cause)
                    }

                val newJob =
                    usageStateRepository
                        .observeSnapshot()
                        .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                        .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                        .onEach { snapshot ->
                            try {
                                processSnapshot(snapshot)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("UpdateCoordinator: Failed to process snapshot in observation loop", e)
                            }
                        }.launchIn(scope + handler)

                if (!observationJob.compareAndSet(null, newJob)) {
                    newJob.cancel()
                    return
                }

                launchMonitoringJob(newJob)
            }
        }

    private suspend fun cleanupOldObservation(currentJob: Job?) {
        val oldJob = observationJob.get()
        if (oldJob != null) {
            oldJob.cancelAndJoinWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
            if (oldJob.isActive) {
                AppLogger.w(
                    "UpdateCoordinator: previous observation job did not terminate in time, forcing cancel",
                )
                oldJob.cancel()
                try {
                    oldJob.join()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Джоб завершён принудительно
                }
            }
            observationJob.compareAndSet(oldJob, null)
        }

        // Cancel previous monitoring coroutine if it's not the current one
        val oldMonitoringJob = monitoringJob.get()
        if (oldMonitoringJob != null && oldMonitoringJob !== currentJob) {
            oldMonitoringJob.cancel()
        }
    }

    private fun launchMonitoringJob(newJob: Job) {
        // Мониторинг завершения: если наблюдение упало — перезапускаем с экспоненциальной задержкой.
        val newMonitoringJob =
            scope.launch {
                    newJob.join()
                    if (observationJob.get() !== newJob) return@launch
                    if (!isObservationStarted.get()) return@launch

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

                    if (isObservationStarted.get() && (observationJob.get() === newJob)) {
                        restartObservation()
                    }
                }
        monitoringJob.set(newMonitoringJob)
        }

    private fun calculateRestartDelay(restartCount: Int): Long {
        val exponent = (restartCount - 1).coerceAtMost(MAX_RESTART_DELAY_EXPONENT)
        return (INITIAL_RESTART_DELAY_MS * (1 shl exponent)).coerceAtMost(MAX_RESTART_DELAY_MS)
    }

        /**
         * Restarts the observation of usage state snapshots.
         *
         * Safe to call from any coroutine context.
         */
        suspend fun restartObservation() {
            widgetUpdateMutex.withLock {
                lastSnapshotHash = null
                consecutiveUpdateFailures = 0
                _status.value = TrackingStatus.Idle
            }
            setupObservation()
        }

    @Suppress("ReturnCount")
    private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
        val newHash = SnapshotUtils.calculateContentHash(snapshot)

        if (snapshot.suppressWidgetUpdate) {
            widgetUpdateMutex.withLock { lastSnapshotHash = newHash }
            return
        }

        val shouldUpdate = checkShouldUpdate(newHash)
        if (!shouldUpdate) return

        // Обновление виджета при отсутствии активных SIM выполняется
        // намеренно — виджет должен корректно отобразить состояние
        // "No SIM" или "No Data".
        val result = performWidgetUpdate()
        handleUpdateResult(result)
    }

    private suspend fun checkShouldUpdate(newHash: Int): Boolean =
            widgetUpdateMutex.withLock {
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
                lastSnapshotHash = newHash
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

    private suspend fun handleUpdateResult(result: WidgetUpdateResult) {
        widgetUpdateMutex.withLock {
            if (result.isSuccess) {
                _status.value = TrackingStatus.Active
                observationRestartCount.set(0)
                consecutiveUpdateFailures = 0
            } else if (result is WidgetUpdateResult.Failure && !result.wasCoalesced) {
                processFailureResult(result)
            }
        }
    }

    private fun processFailureResult(result: WidgetUpdateResult.Failure) {
        when (result.reason) {
            WidgetUpdateResult.FailureReason.TRANSIENT -> {
                lastSnapshotHash = null
                handleUpdateFailure()
            }

            WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED -> {
                AppLogger.e(
                    "UpdateCoordinator: terminal limit exceeded (TransactionTooLarge), " +
                        "keeping current hash",
                )
                _status.value =
                    TrackingStatus.Error(
                            TrackingStatus.ErrorType.NOT_SUPPORTED,
                        "Widget size limit exceeded",
                        )
                handleUpdateFailure()
            }

            WidgetUpdateResult.FailureReason.TERMINAL -> {
                // For terminal failures we don't reset hash to avoid immediate retry loops
                AppLogger.w("UpdateCoordinator: terminal failure encountered, keeping current hash")
                _status.value =
                    TrackingStatus.Error(
                            TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Terminal widget update failure",
                        )
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

            return if (force) {
                val success = performForcedUpdate()
                dataRefreshNotifier.notifyRefresh(isManual = true, suppressWidgetUpdate = true)
                success
            } else {
                dataRefreshNotifier.notifyRefresh(isManual = false, suppressWidgetUpdate = false)
                // Реактивный путь в init обработает обновление, если данные изменились.
                // Мы возвращаем true, так как уведомление успешно отправлено.
                true
            }
        }

    private suspend fun performForcedUpdate(): Boolean =
        widgetUpdateMutex.withLock {
            if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                AppLogger.w(
                    "UpdateCoordinator: forced widget update suppressed due to too many failures " +
                        "($consecutiveUpdateFailures)",
                )
                return@withLock false
            }

            try {
                var result = widgetUpdater.updateAll()

                if (!result.isSuccess) {
                    val failure = result as? WidgetUpdateResult.Failure
                    val isRetryable = failure?.isRetryable ?: false
                    if (isRetryable) {
                        AppLogger.w("Force widget update failed with retryable error, retrying once")
                        result = widgetUpdater.updateAll()
                    } else {
                        AppLogger.w("Force widget update failed with terminal error, not retrying")
                    }
                }

                if (result.isSuccess) {
                    consecutiveUpdateFailures = 0
                    observationRestartCount.set(0)
                    lastSnapshotHash =
                        SnapshotUtils.calculateContentHash(usageStateRepository.observeSnapshot().value)
                    true
                } else {
                    val isRetryable = (result as? WidgetUpdateResult.Failure)?.isRetryable ?: true
                    if (isRetryable) {
                        consecutiveUpdateFailures++
                    }
                    AppLogger.w(
                        "Force widget update reported failure ($consecutiveUpdateFailures), " +
                            "isRetryable=$isRetryable",
                    )
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("Force widget update failed, hash not updated", e)
                if (isRetryableException(e)) {
                    lastSnapshotHash = null
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
