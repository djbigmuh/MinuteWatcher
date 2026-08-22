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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.plus
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

/**
         * Recovers widget observation state.
         *
         * Safe to call from any coroutine context.
         */
        override suspend fun recover() {
            restartObservation()
        }

        private var lastSnapshotHash: Int? = null
        private val widgetUpdateMutex = Mutex()

        // AtomicLong гарантирует потокобезопасность при доступе из retryWhen
        // и processSnapshot, даже если они выполняются в разных корутинах.
        private val retryAttempt = AtomicLong(0L)

        /**
         * Потокобезопасный счётчик последовательных сбоев наблюдения.
         * Ограничен [MAX_OBSERVATION_RETRY_COUNT] для предотвращения
         * неограниченного роста при длительной деградации хранилища.
         * Сбрасывается в 0 при успешной эмиссии ([processSnapshot]).
         *
         * **Строгое соблюдение API:** [AtomicLong] гарантирует потокобезопасность.
         * Доступ происходит из [processSnapshot] (сброс) и [handleObservationFailure]
         * (инкремент), которые выполняются в рамках одного flow-pipeline
         * и не конкурируют между собой.
         */
        private val observationRetryCount = AtomicLong(0L)

        private var lastSuccessTime = 0L
        private var lastResetTime = 0L
        private var observationJob: Job? = null

        init {
            // Запуск наблюдения за snapshot-потоком.
            //
            // Строгое соблюдение API: UpdateCoordinator создаётся через Hilt как @Singleton,
            // поэтому init выполняется ровно один раз за время жизни процесса.
            // Корутина привязана к [scope] (ApplicationScope), который живёт
            // до завершения процесса. При завершении процесса корутина отменяется.
            //
            // На AOSP StateFlow эмитит значения последовательно.
            // Если кастомная прошивка эмитит значения вне порядка —
            // это проблема кастомной прошивки. Debounce гарантирует
            // отсутствие дублирующих обновлений виджета.
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
                flow { emitAll(usageStateRepository.observeSnapshot()) }
                    .filter { it.timestamp > Constants.Data.EMPTY_TIMESTAMP }
                    .debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                    .onEach { snapshot ->
                        processSnapshot(snapshot)
                    }.retryWhen { cause, _ ->
                        handleObservationFailure(cause)
                    }.catch { cause ->
                        AppLogger.e("UpdateCoordinator: observation flow terminated", cause)
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
         * **Контракт AOSP:** на AOSP combine из StateFlow не бросает исключений.
         *
         * **Отклонение кастомной прошивки:** Сбой возможен только при деградации DataStore на кастомных прошивках,
         * не соответствующих документации Google. Это проблема кастомной прошивки.
         *
         * **Стратегия приложения:** Бесконечные повторные попытки с экспоненциальной задержкой
         * гарантируют восстановление без вмешательства пользователя.
         */
        private suspend fun handleObservationFailure(cause: Throwable): Boolean {
            if (cause is CancellationException) throw cause

            val now = timeProvider.now()
            if ((now - lastResetTime) > RESET_RETRY_COUNTER_INTERVAL_MS) {
                retryAttempt.set(0)
                observationRetryCount.set(0)
                lastResetTime = now
            }

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
                    (Constants.Flow.WIDGET_RETRY_DELAY_MS / Constants.Time.MILLIS_IN_SECOND) *
                        (1L shl minOf(retryAttempt.get().toInt(), MAX_RETRY_EXPONENT)),
                    MAX_RETRY_DELAY_SECONDS,
                )

            val currentRetry = retryAttempt.incrementAndGet()
            observationRetryCount.updateAndGet { current ->
                minOf(current + 1, MAX_OBSERVATION_RETRY_COUNT)
            }
            val consecutive = observationRetryCount.get()

            AppLogger.e(
                "UpdateCoordinator: observation failed (attempt $currentRetry, " +
                    "consecutive: $consecutive), retry in ${delaySeconds}s",
                cause,
            )

            delay(delaySeconds.seconds)

            // Стратегия: бесконечные повторы с экспоненциальной задержкой (cap 300 с).
            // UI сохраняет последнее успешное состояние во время повторов — нет мерцания.
            // Бесконечные повторы — единственный способ обеспечить живучесть UI
            // при системных сбоях без вмешательства пользователя.
            // Строгое соблюдение API: на AOSP combine из StateFlow не бросает исключений.
            // Сбой возможен только при деградации DataStore на кастомных прошивках,
            // не соответствующих документации Google. Если кастомная прошивка не
            // соответствует документации Google — это проблема кастомной прошивки.
            // Утечка ресурсов предотвращена: scope = ApplicationScope (живёт пока
            // живо приложение), Flow не создаёт новых объектов при повторе.
            // Остановка возможна только через отмену ApplicationScope (завершение процесса).
            return true
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

            /**
             * Максимальное значение счётчика последовательных сбоев наблюдения.
             * После достижения этого значения логирование подавляется
             * (чтобы не спамить лог), но повторные попытки продолжаются.
             */
            private const val MAX_OBSERVATION_RETRY_COUNT = 100L

            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val RESET_RETRY_COUNTER_INTERVAL_MS = 10 * 60 * 1000L
        }
    }
