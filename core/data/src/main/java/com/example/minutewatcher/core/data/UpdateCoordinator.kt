package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        private val timeProvider: TimeProvider,
    ) : DataChangeNotifier {
        private val lastSnapshotHash = MutableStateFlow<Int?>(null)
        private val widgetUpdateMutex = Mutex()

        private var retryAttempt = 0L
        private var observationRetryCount = 0L
        private var lastSuccessTime = 0L

        init {
            startObservation()
        }

        @OptIn(FlowPreview::class)
        private fun startObservation() {
            val handler =
                CoroutineExceptionHandler { _, cause ->
                    AppLogger.e("UpdateCoordinator: observation coroutine failed", cause)
                }
            scope.launch(handler) {
                usageStateRepository
                    .observeSnapshot()
                    .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                    .distinctUntilChanged { old, new ->
                        SnapshotUtils.calculateContentHash(old) ==
                            SnapshotUtils.calculateContentHash(new)
                    }.debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .onEach { snapshot ->
                        processSnapshot(snapshot)
                    }.retryWhen { cause, _ ->
                        handleObservationFailure(cause)
                    }.catch { cause ->
                        AppLogger.e("UpdateCoordinator: observation flow terminated", cause)
                    }.onCompletion { cause ->
                        if (cause == null && observationRetryCount > MAX_OBSERVATION_RETRIES) {
                            AppLogger.e("UpdateCoordinator: observation stopped after $observationRetryCount retries")
                        }
                    }.collect {}
            }
        }

        private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
            // Сбрасываем счетчик ошибок при любом успешном получении данных для самовосстановления.
            observationRetryCount = 0
            val now = timeProvider.now()
            if ((now - lastSuccessTime) > RESET_RETRY_COUNTER_INTERVAL_MS) {
                retryAttempt = 0
            }
            lastSuccessTime = now

            val newHash = SnapshotUtils.calculateContentHash(snapshot)

            if (snapshot.suppressWidgetUpdate) {
                lastSnapshotHash.value = newHash
                return
            }

            if (newHash != lastSnapshotHash.value) {
                lastSnapshotHash.value = newHash
                // Обновление виджета при отсутствии активных SIM выполняется
                // намеренно — виджет должен корректно отобразить состояние
                // "No SIM" или "No Data".
                widgetUpdateMutex.withLock {
                    widgetUpdater.updateAll()
                }
            }
        }

        private suspend fun handleObservationFailure(cause: Throwable): Boolean {
            if (cause is CancellationException) throw cause

            observationRetryCount++
            if (observationRetryCount > MAX_OBSERVATION_RETRIES) {
                AppLogger.e(
                    "UpdateCoordinator: observation stopped after $MAX_OBSERVATION_RETRIES " +
                        "consecutive failures. Restart the app to restore background updates.",
                    cause,
                )
                return false
            }

            val now = timeProvider.now()
            if ((now - lastSuccessTime) > RESET_RETRY_COUNTER_INTERVAL_MS) {
                retryAttempt = 0
            }

            lastSnapshotHash.value = null
            val delaySeconds =
                minOf(
                    INITIAL_RETRY_DELAY_SECONDS * (1L shl minOf(retryAttempt.toInt(), MAX_RETRY_EXPONENT)),
                    MAX_RETRY_DELAY_SECONDS,
                )

            retryAttempt++

            AppLogger.e(
                "UpdateCoordinator: observation failed (attempt $retryAttempt, " +
                    "consecutive: $observationRetryCount), retry in ${delaySeconds}s",
                cause,
            )

            delay(delaySeconds.seconds)
            return true
        }

        /**
         * Уведомляет об изменении данных.
         *
         * При [force] = true выполняется немедленное обновление виджетов через [performForcedUpdate]
         * и запускается обновление данных. При этом обновление виджета в реактивном цикле
         * подавляется (через `suppressWidgetUpdate` = true), чтобы избежать дублирования.
         *
         * При [force] = false запускается только обновление данных. Виджет обновится
         * реактивно через блок инициализации только в том случае, если новые данные приведут
         * к изменению хэша содержимого ([SnapshotUtils.calculateContentHash]).
         * Если данные не изменились, но требуется принудительное обновление (например,
         * при изменении настроек оформления), необходимо использовать [force] = true.
         *
         * @param updateWidget если true — инициирует обновление.
         * @param force если true — принудительное немедленное обновление виджета.
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
                    widgetUpdater.updateAll()
                    if (snapshot.timestamp > Constants.Data.EMPTY_TIMESTAMP) {
                        lastSnapshotHash.value = SnapshotUtils.calculateContentHash(snapshot)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Force widget update failed, hash not updated", e)
                    lastSnapshotHash.value = null
                }
            }
        }

        companion object {
            private const val INITIAL_RETRY_DELAY_SECONDS = 5L
            private const val MAX_RETRY_EXPONENT = 6
            private const val MAX_RETRY_DELAY_SECONDS = 300L
            private const val RESET_RETRY_COUNTER_INTERVAL_MS = 10 * 60 * 1000L
            private const val MAX_OBSERVATION_RETRIES = 10L
        }
    }
