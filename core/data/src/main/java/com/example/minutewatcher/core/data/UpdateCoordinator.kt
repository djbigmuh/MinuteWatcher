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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
         * Recovers widget observation state.
         *
         * Safe to call from any coroutine context.
         */
        override suspend fun recover() {
            restartObservation()
        }

        private var lastSnapshotHash: Int? = null
        private var consecutiveUpdateFailures = 0
        private val widgetUpdateMutex = Mutex()
        private val initMutex = Mutex()

        private var observationJob: Job? = null

        init {
            // Запуск наблюдения за snapshot-потоком.
            //
            // Строгое соблюдение API: UpdateCoordinator создаётся через Hilt как @Singleton,
            // поэтому init выполняется ровно один раз за время жизни процесса.
            // Корутина привязана к [scope] (ApplicationScope), который живёт
            // до завершения процесса. При завершении процесса корутина отменяется.
            scope.launch {
                initMutex.withLock {
                    setupObservation()
                }
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun setupObservation() {
            observationJob?.cancel()
            observationJob?.join()

            val handler =
                CoroutineExceptionHandler { _, cause ->
                    AppLogger.e("UpdateCoordinator: observation coroutine failed", cause)
                }
            observationJob =
                flow { emitAll(usageStateRepository.observeSnapshot()) }
                    .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .onEach { snapshot ->
                        processSnapshot(snapshot)
                    }
                    // Строгое соблюдение API: launchIn(scope + handler) создаёт дочернюю
                    // корутину scope, которая будет отменена при отмене scope. Это
                    // гарантирует, что наблюдение за снапшотами будет остановлено при
                    // завершении приложения. На AOSP ApplicationScope живёт до завершения
                    // процесса. Если кастомная прошивка убивает процесс без вызова
                    // Application.onTerminate() — это проблема кастомной прошивки.
                    .launchIn(scope + handler)
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
                }
                setupObservation()
            }
        }

        private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
            val newHash = SnapshotUtils.calculateContentHash(snapshot)

            if (snapshot.suppressWidgetUpdate) {
                widgetUpdateMutex.withLock {
                    lastSnapshotHash = newHash
                }
                return
            }

            // Обновление виджета при отсутствии активных SIM выполняется
            // намеренно — виджет должен корректно отобразить состояние
            // "No SIM" или "No Data".
            catchingCancellation(
                block = {
                    widgetUpdateMutex.withLock {
                        if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                            AppLogger.d(
                                "UpdateCoordinator: widget update suppressed due to too many failures " +
                                    "($consecutiveUpdateFailures)",
                            )
                            return@withLock
                        }

                        if (newHash != lastSnapshotHash) {
                            val success = widgetUpdater.updateAll()
                            if (success) {
                                lastSnapshotHash = newHash
                                if (consecutiveUpdateFailures > 0) {
                                    AppLogger.d(
                                        "UpdateCoordinator: widget update recovered, resetting failure counter",
                                    )
                                    consecutiveUpdateFailures = 0
                                }
                            } else {
                                consecutiveUpdateFailures++
                                AppLogger.w(
                                    "UpdateCoordinator: widget update failed ($consecutiveUpdateFailures), " +
                                        "hash not updated",
                                )
                                if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                                    AppLogger.e(
                                        "UpdateCoordinator: reached max consecutive failures. Updates suppressed.",
                                    )
                                }
                            }
                        } else {
                            AppLogger.d("UpdateCoordinator: skipping reactive update, hash match ($newHash)")
                        }
                    }
                },
            ) { e ->
                AppLogger.w("UpdateCoordinator: widget update failed in processSnapshot", e)
                widgetUpdateMutex.withLock {
                    consecutiveUpdateFailures++
                    AppLogger.w(
                        "UpdateCoordinator: widget update failed ($consecutiveUpdateFailures), hash not updated",
                    )
                    if (consecutiveUpdateFailures >= Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                        AppLogger.e("UpdateCoordinator: reached max consecutive failures. Updates suppressed.")
                    }
                }
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
