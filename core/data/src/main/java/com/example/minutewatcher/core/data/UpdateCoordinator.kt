package com.example.minutewatcher.core.data

import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
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
    ) : DataChangeNotifier,
        TrackingStatusProvider {
        override val key: String = "widget_observation"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override suspend fun recover() {
            restartObservation()
        }

        private var lastSnapshotHash: Int? = null
        private val widgetUpdateMutex = Mutex()

        // AtomicLong гарантирует потокобезопасность при доступе из retryWhen
        // и processSnapshot, даже если они выполняются в разных корутинах.
        private val retryAttempt = AtomicLong(0L)
        private val observationRetryCount = AtomicLong(0L)

        private var lastSuccessTime = 0L
        private var lastResetTime = 0L
        private var observationJob: Job? = null

        init {
            lastResetTime = timeProvider.now()
            startObservation()
        }

        @OptIn(FlowPreview::class)
        private fun startObservation() {
            val handler =
                CoroutineExceptionHandler { _, cause ->
                    AppLogger.e("UpdateCoordinator: observation coroutine failed", cause)
                }
            observationJob =
                scope.launch(handler) {
                    flow { emitAll(usageStateRepository.observeSnapshot()) }
                        .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                        .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                        .onEach { snapshot ->
                            processSnapshot(snapshot)
                        }.retryWhen { cause, _ ->
                            handleObservationFailure(cause)
                        }.catch { cause ->
                            AppLogger.e("UpdateCoordinator: observation flow terminated", cause)
                        }.collect {}
                }
        }

        fun restartObservation() {
            observationJob?.cancel()
            observationRetryCount.set(0)
            startObservation()
        }

        private suspend fun processSnapshot(snapshot: GlobalSnapshot) {
            // Наблюдение успешно — snapshot доставлен. Сбрасываем счётчик
            // сбоев наблюдения независимо от результата обновления виджета.
            // Это гарантирует, что после восстановления счётчик не остаётся
            // в состоянии "много ошибок" и не влияет на логирование.
            observationRetryCount.set(0)

            val now = timeProvider.now()
            if ((now - lastSuccessTime) > RESET_RETRY_COUNTER_INTERVAL_MS) {
                retryAttempt.set(0)
            }
            lastSuccessTime = now

            val newHash = SnapshotUtils.calculateContentHash(snapshot)

            if (snapshot.suppressWidgetUpdate) {
                widgetUpdateMutex.withLock {
                    lastSnapshotHash = newHash
                }
                if (_status.value is TrackingStatus.Error) {
                    _status.value = TrackingStatus.Active
                }
                return
            }

            // Обновление виджета при отсутствии активных SIM выполняется
            // намеренно — виджет должен корректно отобразить состояние
            // "No SIM" или "No Data".
            try {
                widgetUpdateMutex.withLock {
                    if (newHash != lastSnapshotHash) {
                        val success = widgetUpdater.updateAll()
                        if (success) {
                            lastSnapshotHash = newHash
                            if (_status.value is TrackingStatus.Error) {
                                _status.value = TrackingStatus.Active
                            }
                            lastResetTime = now
                        } else {
                            AppLogger.w("UpdateCoordinator: widget update failed, hash not updated")
                        }
                    } else {
                        AppLogger.d("UpdateCoordinator: skipping reactive update, hash match ($newHash)")
                        if (_status.value is TrackingStatus.Error) {
                            _status.value = TrackingStatus.Active
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("UpdateCoordinator: widget update failed in processSnapshot", e)
            }
        }

        /**
         * Обработчик сбоев наблюдения за snapshot.
         *
         * Стратегия:
         * 1. Бесконечные повторные попытки с экспоненциальной задержкой,
         *    ограниченной [MAX_RETRY_DELAY_SECONDS].
         * 2. [observationRetryCount] отслеживает последовательные сбои для логирования.
         * 3. [retryAttempt] и [observationRetryCount] сбрасываются каждые
         *    [RESET_RETRY_COUNTER_INTERVAL_MS] (10 минут) для предотвращения накопления.
         *
         * **Строгое соблюдение API:**
         * На AOSP combine из StateFlow не бросает исключений; сбой возможен только
         * при деградации DataStore на кастомных прошивках, не соответствующих
         * документации Google. Если кастомная прошивка не соответствует
         * документации Google — это проблема кастомной прошивки.
         * Бесконечные повторные попытки гарантируют восстановление без
         * вмешательства пользователя.
         */
        private suspend fun handleObservationFailure(cause: Throwable): Boolean {
            if (cause is CancellationException) throw cause

            val now = timeProvider.now()
            if ((now - lastResetTime) > RESET_RETRY_COUNTER_INTERVAL_MS) {
                retryAttempt.set(0)
                observationRetryCount.set(0)
                lastResetTime = now
            }

            observationRetryCount.incrementAndGet()

            /**
             * Сбрасываем lastSnapshotHash в null при сбое, чтобы гарантировать
             * полную переиндексацию и принудительное обновление виджетов
             * при следующем успешном получении данных.
             */
            widgetUpdateMutex.withLock {
                lastSnapshotHash = null
            }

            val delaySeconds =
                minOf(
                    (Constants.Flow.WIDGET_RETRY_DELAY_MS / Constants.MILLIS_PER_SECOND) *
                        (1L shl minOf(retryAttempt.get().toInt(), MAX_RETRY_EXPONENT)),
                    MAX_RETRY_DELAY_SECONDS,
                )

            val currentRetry = retryAttempt.incrementAndGet()

            AppLogger.e(
                "UpdateCoordinator: observation failed (attempt $currentRetry, " +
                    "consecutive: ${observationRetryCount.get()}), retry in ${delaySeconds}s",
                cause,
            )

            delay(delaySeconds.seconds)

            // Стратегия бесконечных повторов с ограниченной задержкой (cap = MAX_RETRY_DELAY_SECONDS).
            //
            // Обоснование: этот поток наблюдения за снимками состояния питает UI и виджеты.
            // Если поток прерывается ошибкой, UI сохраняет последнее успешное значение.
            // Бесконечный повтор гарантирует, что UI автоматически восстановится после
            // устранения причины сбоя, без вмешательства пользователя.
            //
            // Строгое соблюдение API: на AOSP observeSnapshot() не бросает исключений.
            // Сбой возможен только при деградации DataStore на кастомных прошивках.
            // Утечка ресурсов предотвращается:
            // 1. Cap на задержку (MAX_RETRY_DELAY_SECONDS = 300с).
            // 2. scope = ApplicationScope — живёт пока живо приложение.
            // 3. Flow не создаёт новых объектов при повторе, только перечитывает значения.
            //
            // Остановка возможна только через отмену ApplicationScope (завершение процесса).
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
                    val success = widgetUpdater.updateAll()
                    if (success && (snapshot.timestamp > Constants.Data.EMPTY_TIMESTAMP)) {
                        lastSnapshotHash = SnapshotUtils.calculateContentHash(snapshot)
                    } else if (!success) {
                        AppLogger.w("Force widget update reported failure, hash not updated")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Force widget update failed, hash not updated", e)
                    lastSnapshotHash = null
                }
            }
        }

        companion object {
            private const val MAX_RETRY_EXPONENT = 6
            private const val MAX_RETRY_DELAY_SECONDS = 300L

            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val RESET_RETRY_COUNTER_INTERVAL_MS = 10 * 60 * 1000L
        }
    }
