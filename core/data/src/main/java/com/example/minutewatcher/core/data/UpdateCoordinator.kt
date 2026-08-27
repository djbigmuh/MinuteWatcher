package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.catchingCancellation
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Единый механизм обновления.
 *
 * Координатор является Singleton и использует ApplicationScope, поэтому цикл
 * наблюдения за снимками состояния (observeSnapshot) живет в течение всего
 * времени работы приложения.
 */
@Singleton
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
    private val widgetUpdateMutex = Mutex()
    private val initMutex = Mutex()

    private val isObservationStarted = AtomicBoolean(false)
    private val observationJob = AtomicReference<Job?>(null)

    init {
        ensureObservationStarted()
    }

        /**
         * Recovers widget observation state.
         *
         * Safe to call from any coroutine context.
         */
        override suspend fun recover() {
            if (!isObservationStarted.get()) {
                ensureObservationStarted()
            } else {
                restartObservation()
            }
        }

        private fun ensureObservationStarted() {
            if (isObservationStarted.get()) return
            scope.launch {
                initMutex.withLock {
                    if (isObservationStarted.compareAndSet(false, true)) {
                        setupObservation()
                    }
                }
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun setupObservation() {
            val oldJob = observationJob.get()
            if (oldJob != null) {
                val completed =
                    withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                        oldJob.cancelAndJoin()
                    }
                if (completed == null) {
                    val errorMsg = "UpdateCoordinator: previous observation job did not terminate in time"
                    AppLogger.e(errorMsg)
                    throw IllegalStateException(errorMsg)
                }
                observationJob.compareAndSet(oldJob, null)
            }

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
                        processSnapshot(snapshot)
                    }
                    .launchIn(scope + handler)

            if (!observationJob.compareAndSet(null, newJob)) {
                newJob.cancel()
                return
            }

            // Мониторинг завершения: если наблюдение упало — перезапускаем через 5 секунд.
            scope.launch {
                newJob.join()
                // Если текущий job изменился (т.е. setupObservation вызван снова), ничего не делаем.
                if (observationJob.get() !== newJob) return@launch
                if (!isObservationStarted.get()) return@launch // Скоуп закрыт штатно
                AppLogger.w("UpdateCoordinator: observation terminated unexpectedly, scheduling restart")
                kotlinx.coroutines.delay(5000.milliseconds)
                if (isObservationStarted.get() && observationJob.get() === newJob) {
                    restartObservation()
                }
            }
        }

        /**
         * Restarts the observation of usage state snapshots.
         *
         * Safe to call from any coroutine context.
         */
        suspend fun restartObservation() {
            initMutex.withLock {
                widgetUpdateMutex.withLock {
                    lastSnapshotHash = null
                    consecutiveUpdateFailures = 0
                    _status.value = TrackingStatus.Idle
                }
                setupObservation()
            }
        }

        private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
            if (!coroutineContext.isActive) return
            val newHash = SnapshotUtils.calculateContentHash(snapshot)

            if (snapshot.suppressWidgetUpdate) {
                widgetUpdateMutex.withLock {
                    lastSnapshotHash = newHash
                }
                return
            }

            val shouldUpdate =
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
                    true
                }

            if (!shouldUpdate) return

            // Обновление виджета при отсутствии активных SIM выполняется
            // намеренно — виджет должен корректно отобразить состояние
            // "No SIM" или "No Data".
            val success =
                catchingCancellation(
                    block = {
                        withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                            widgetUpdater.updateAll()
                        } ?: false
                    },
                    onError = { e ->
                        AppLogger.w("UpdateCoordinator: widget update failed in processSnapshot", e)
                    },
                ) ?: false

            widgetUpdateMutex.withLock {
                if (success) {
                    handleUpdateSuccess(newHash)
                } else {
                    handleUpdateFailure()
                }
            }
        }

        private fun handleUpdateSuccess(newHash: Int) {
            lastSnapshotHash = newHash
            _status.value = TrackingStatus.Active
            if (consecutiveUpdateFailures > 0) {
                AppLogger.d("UpdateCoordinator: widget update recovered, resetting failure counter")
                consecutiveUpdateFailures = 0
            }
        }

        private fun handleUpdateFailure() {
            consecutiveUpdateFailures++
            AppLogger.w(
                "UpdateCoordinator: widget update failed ($consecutiveUpdateFailures), " +
                    "hash not updated",
            )
            if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                AppLogger.e("UpdateCoordinator: reached max consecutive failures. Updates suppressed.")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Widget observation failed after $consecutiveUpdateFailures consecutive errors",
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
        ) {
            if (!updateWidget) return

            if (force) {
                performForcedUpdate()
                dataRefreshNotifier.notifyRefresh(isManual = true, suppressWidgetUpdate = true)
            } else {
                dataRefreshNotifier.notifyRefresh(isManual = false, suppressWidgetUpdate = false)
                // Реактивный путь в init обработает обновление, если данные изменились.
                // Если данные не изменились, но вызывающий явно запросил обновление,
                // это покрывается только через force=true.
            }
        }

        private suspend fun performForcedUpdate() {
            widgetUpdateMutex.withLock {
                try {
                    val snapshot = usageStateRepository.observeSnapshot().value
                    val success = widgetUpdater.updateAll()
                    if (success) {
                        consecutiveUpdateFailures = 0
                        if (snapshot.timestamp > Constants.Data.EMPTY_TIMESTAMP) {
                            lastSnapshotHash = SnapshotUtils.calculateContentHash(snapshot)
                        }
                    } else {
                        consecutiveUpdateFailures++
                        AppLogger.w(
                            "Force widget update reported failure ($consecutiveUpdateFailures), hash not updated",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Force widget update failed, hash not updated", e)
                    lastSnapshotHash = null
                }
            }
        }
    }
