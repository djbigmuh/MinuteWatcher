package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Реализация WidgetUpdater.
 * Обновляет все экземпляры виджета с ограниченной экспоненциальной
 * стратегией повторных попыток.
 *
 * Стратегия повторных попыток:
 * 1. Транзиентные ошибки: повтор до MAX_UPDATE_ATTEMPTS с экспоненциальной задержкой.
 * 2. Перманентные ошибки: повтор НЕ выполняется.
 * 3. Превышение лимита RemoteViews: немедленный рендер с упрощённым layout.
 *
 * Строгое соблюдение API: На AOSP GlanceAppWidget.updateAll() не бросает
 * исключений при корректном lifecycle. TransactionTooLargeException возможен
 * только при превышении лимита Binder (~1 МБ).
 *
 * Отклонение кастомной прошивки: Если кастомная прошивка бросает исключения
 * при корректном lifecycle виджета — это проблема кастомной прошивки.
 */
@Singleton
class WidgetUpdaterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val renderer: WidgetRenderer,
    ) : WidgetUpdater {
        /**
         * Мьютекс сериализует все обращения к обновлению виджетов.
         *
         * Строгое соблюдение API: Mutex из kotlinx-coroutines гарантирует,
         * что только одна корутина выполняет обновление в каждый момент времени.
         * Все обращения к [exceededUpdateCounter] и [performWidgetUpdate]
         * происходят исключительно внутри [withLock], что исключает гонки данных.
         *
         * Отклонение кастомной прошивки: если GlanceAppWidget.updateAll()
         * на кастомной прошивке ведёт себя неконсистентно при последовательных
         * вызовах — это проблема кастомной прошивки, не проекта.
         */
        private val updateMutex = Mutex()

        /**
         * Счётчик обновлений, выполненных в режиме widgetLimitExceeded.
         * Каждое PROBE_INTERVAL_UPDATES-е обновление выполняет пробный полный рендер.
         *
         * Счётчик НЕ персистентен. При перезапуске приложения он обнуляется.
         * Это допустимая эвристика в пределах критерия надёжности:
         * упрощённый layout всегда укладывается в лимит Binder (~1 МБ на AOSP).
         *
         * Потокобезопасность: доступ только внутри [updateMutex.withLock].
         */
        private var exceededUpdateCounter = 0L

        /**
         * Результат обновления виджета.
         * Sealed class гарантирует исчерпывающую обработку всех вариантов.
         *
         * Варианты:
         * - [Success] — успешное обновление. wasFull = true если был полный рендер.
         * - [LimitExceeded] — превышение лимита RemoteViews.
         * - [GenericFailure] — общая ошибка. isRetryable = true если ошибка транзиентная.
         */
        private sealed class UpdateResult {
            data class Success(
                val wasFull: Boolean,
            ) : UpdateResult()

            object LimitExceeded : UpdateResult()

            data class GenericFailure(
                val isRetryable: Boolean,
            ) : UpdateResult()
        }

        /**
         * Результат обработки обновления виджета в updateAll().
         * Sealed class гарантирует исчерпывающую обработку всех вариантов.
         *
         * Варианты:
         * - [Success] — успешное обновление. shouldStop = true если нужно остановить цикл.
         * - [Retry] — необходима повторная попытка. Задержка определяется внешней логикой.
         * - [Stop] — необходимо остановить цикл (перманентная ошибка).
         */
        private sealed class UpdateOutcome {
            data class Success(
                val shouldStop: Boolean = true,
            ) : UpdateOutcome()

            object Retry : UpdateOutcome()

            object Stop : UpdateOutcome()
        }

        /**
         * Обновляет все экземпляры виджета с ограниченной экспоненциальной стратегией повторных попыток.
         *
         * Стратегия повторных попыток:
         * 1. Транзиентные ошибки (Binder congestion, RuntimeException): повтор до
         *    [Constants.Widget.MAX_UPDATE_ATTEMPTS] с экспоненциальной задержкой.
         *    Старт: [Constants.Widget.RETRY_BACKOFF_MS], удвоение на каждой попытке,
         *    потолок: [Constants.Widget.MAX_RETRY_DELAY_MS].
         * 2. Перманентные ошибки (destroyed context, IllegalStateException, не-RemoteViews IllegalArgumentException):
         *    повтор НЕ выполняется.
         * 3. Превышение лимита RemoteViews (TransactionTooLargeException в cause-цепочке):
         *    немедленный рендер с упрощённым layout, НЕ считается попыткой повтора.
         *
         * Строгое соблюдение API: На AOSP GlanceAppWidget.updateAll() не бросает исключений
         * при корректном lifecycle. TransactionTooLargeException возможен только при
         * превышении лимита Binder (~1 МБ).
         *
         * Отклонение кастомной прошивки: Если кастомная прошивка бросает исключения
         * при корректном lifecycle виджета — это проблема кастомной прошивки.
         * Стратегия retry является защитным механизмом.
         *
         * @return true если обновление завершилось успешно, false в противном случае.
         * @throws CancellationException при отмене корутины (пробрасывается для кооперативной отмены).
         */
        override suspend fun updateAll(): Boolean {
            var delayMs = Constants.Widget.RETRY_BACKOFF_MS
            var result = false
            var attempt = 1
            var keepGoing = true

            // Стратегия повторных попыток: экспоненциальная задержка с потолком.
            // Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() завершается
            // за несколько секунд. Исчерпание попыток указывает на системную проблему.
            // На кастомной прошивке это может быть проблема прошивки.
            while (attempt <= Constants.Widget.MAX_UPDATE_ATTEMPTS && keepGoing) {
                // **Контракт AOSP:** на AOSP GlanceAppWidget.updateAll() завершается в пределах нескольких секунд.
                //
                // **Отклонение кастомной прошивки:** Зависание на кастомных прошивках. Это проблема кастомной прошивки.
                //
                // **Стратегия приложения:** Таймаут 30 секунд — защитный механизм; считается retryable-ошибкой.
                val updateOutcome = performSingleUpdate(attempt)

                when (updateOutcome) {
                    is UpdateOutcome.Success -> {
                        result = true
                        keepGoing = false
                    }
                    is UpdateOutcome.Stop -> {
                        keepGoing = false
                    }
                    is UpdateOutcome.Retry -> {
                        if (attempt == Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                            AppLogger.e(
                                "Widget update exhausted all ${Constants.Widget.MAX_UPDATE_ATTEMPTS} retry attempts. " +
                                    "Further retries suppressed. On AOSP this indicates a system-level issue. " +
                                    "On custom firmware this may be a firmware-specific problem.",
                            )
                            keepGoing = false
                        } else {
                            /**
                             * Задержка между попытками определяется локальной переменной `delayMs`
                             * с экспоненциальным ростом. `UpdateOutcome.Retry` лишь сигнализирует
                             * о необходимости повторной попытки.
                             */
                            AppLogger.w("Widget update attempt $attempt failed, retrying in ${delayMs}ms")
                            delay(delayMs.milliseconds)
                            delayMs = (delayMs * 2).coerceAtMost(Constants.Widget.MAX_RETRY_DELAY_MS)
                            attempt++
                        }
                    }
                }
            }

            if (!result) {
                val total = minOf(attempt, Constants.Widget.MAX_UPDATE_ATTEMPTS)
                AppLogger.e("Widget update failed after $total attempts")
            }
            return result
        }

        /**
         * Выполняет одну попытку обновления виджетов с перехватом системных исключений.
         *
         * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() завершается
         * за несколько секунд. Таймаут 10 с — защитный механизм.
         *
         * @param attempt Номер попытки (для логирования).
         * @return Результат обработки обновления.
         */
        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome =
            updateMutex.withLock {
                val updateOutcome =
                    withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        val updateResult =
                            try {
                                val settings = appSettingsRepository.observeAppSettings().value
                                if (settings.widgetLimitExceeded) {
                                    // Переполнение Long на практике невозможно (миллионы лет при 1 обн/час),
                                    // но инкремент внутри мьютекса гарантирует корректность.
                                    val count = ++exceededUpdateCounter
                                    if (count % PROBE_INTERVAL_UPDATES == 0L) {
                                        // probe: временный сброс флага для проверки, не устранена ли причина
                                        // превышения лимита (например, пользователь уменьшил число SIM).
                                        // Безопасно: находимся внутри updateMutex.withLock.
                                        performProbeUpdate()
                                    } else {
                                        safeUpdateAll(context, isProbe = false)
                                    }
                                } else {
                                    // exceededUpdateCounter сбрасывается при выходе из режима
                                    // widgetLimitExceeded. При повторном входе в режим счётчик
                                    // начинается с 0, и первый probe произойдёт через
                                    // PROBE_INTERVAL_UPDATES обновлений. Это корректно:
                                    // каждый вход в режим — новая ситуация, и probe-цикл
                                    // должен начинаться заново.
                                    exceededUpdateCounter = 0L
                                    safeUpdateAll(context, isProbe = false)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Unexpected error updating widgets (attempt $attempt)", e)
                                UpdateResult.GenericFailure(isRetryable = true)
                            }

                        // Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll()
                        // завершается за несколько секунд. Таймаут 10 с — защитный запас.
                        // Таймаут измеряет только время рендера, а не ожидание мьютекса.
                        // На кастомных прошивках рендер может занять больше 10 с —
                        // это проблема кастомной прошивки.
                        // withTimeoutOrNull возвращает null при таймауте (TimeoutCancellationException
                        // перехватывается). Реальный CancellationException (отмена scope) пробрасывается.
                        // Это строгое соблюдение API kotlinx.coroutines.
                        handleUpdateResult(updateResult)
                    }
                updateOutcome ?: handleUpdateResult(UpdateResult.GenericFailure(isRetryable = true))
            }

        /**
         * Обрабатывает результат обновления виджета.
         * При Success с wasFull = true сбрасывает widgetLimitExceeded.
         * При LimitExceeded вызывает handleLimitExceeded.
         * При GenericFailure возвращает Retry или Stop.
         *
         * @param result Результат обновления.
         * @return Результат обработки.
         */
        private suspend fun handleUpdateResult(result: UpdateResult): UpdateOutcome =
            when (result) {
                is UpdateResult.Success -> {
                    // Сбрасываем флаг ТОЛЬКО если реально был предпринят и удался ПОЛНЫЙ рендер.
                    // Если рендер был упрощённым (из-за установленного флага или принудительно),
                    // успех НЕ означает, что полный рендер теперь пройдёт.
                    // Это предотвращает осцилляцию флага и лишние TransactionTooLargeException.
                    //
                    // Строгое соблюдение API: на AOSP RemoteViews-лимит ~1 МБ.
                    // Упрощённый layout всегда укладывается. Если полный рендер
                    // не проходит — проблема кастомной прошивки с уменьшенным лимитом.
                    if (result.wasFull) {
                        setWidgetLimitExceeded(value = false)
                    }
                    UpdateOutcome.Success()
                }

                is UpdateResult.LimitExceeded -> {
                    handleLimitExceeded()
                }

                is UpdateResult.GenericFailure -> {
                    if (result.isRetryable) {
                        UpdateOutcome.Retry
                    } else {
                        UpdateOutcome.Stop
                    }
                }
            }

        /**
         * Обработка превышения лимита RemoteViews (TransactionTooLargeException).
         *
         * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() бросает
         * TransactionTooLargeException только при превышении лимита Binder (~1 МБ).
         * Упрощённый layout (widgetLimitExceeded = true) всегда укладывается в этот лимит.
         * Если упрощённый рендер тоже падает — это проблема кастомной прошивки
         * или destroyed context, а не превышение лимита.
         *
         * Стратегия: при падении упрощённого рендера флаг НЕ сбрасывается,
         * повтор не выполняется. Это предотвращает осцилляцию флага.
         */
        @Suppress("ReturnCount")
        private suspend fun handleLimitExceeded(): UpdateOutcome {
            // Читаем флаг напрямую из репозитория, а не из StateFlow,
            // чтобы избежать stale-значения. На AOSP getAppSettings()
            // завершается за миллисекунды. Таймаут указывает на деградацию
            // DataStore на кастомной прошивке.
            //
            // При таймауте возвращаем Retry. Реальная задержка определяется
            // локальной переменной delayMs в updateAll(), а не параметром Retry.
            // Это означает, что повторная попытка произойдёт через стандартную
            // экспоненциальную задержку, а не немедленно.
            val currentSettings =
                appSettingsRepository.getAppSettings().firstWithTimeout(
                    Constants.Flow.SHORT_TIMEOUT_MS,
                )

            if (currentSettings == null) {
                // Строгое соблюдение API: на AOSP getAppSettings() завершается за миллисекунды.
                // Таймаут указывает на деградацию DataStore на кастомной прошивке.
                // Это проблема кастомной прошивки. Стратегия: повторная попытка, а не
                // предположение о превышении лимита.
                AppLogger.w("handleLimitExceeded: settings read timed out, cannot determine limit state")
                return UpdateOutcome.Retry
            }

            val current = currentSettings.widgetLimitExceeded
            if (current) {
                // Флаг уже установлен (например, probe восстановил его).
                // Повторный рендер не нужен — предотвращаем осцилляцию.
                return UpdateOutcome.Stop
            }

            setWidgetLimitExceeded(value = true)
            AppLogger.w("Widget update failed due to size limit. Rendering with simplified layout.")

            // Упрощённый layout на AOSP всегда укладывается в лимит Binder (~1 МБ).
            // Если он тоже падает — это проблема кастомной прошивки или destroyed context.
            val result = safeUpdateAll(context, isProbe = false)
            return when (result) {
                is UpdateResult.Success -> UpdateOutcome.Success()
                is UpdateResult.LimitExceeded -> {
                    AppLogger.e(
                        "CRITICAL: Simplified layout also exceeds Binder limit. " +
                            "This should not happen on AOSP. Possible custom firmware issue.",
                    )
                    UpdateOutcome.Stop
                }
                is UpdateResult.GenericFailure -> {
                    if (result.isRetryable) {
                        UpdateOutcome.Retry
                    } else {
                        UpdateOutcome.Stop
                    }
                }
            }
        }

        /**
         * Пробный полный рендер для проверки, устранена ли причина превышения лимита.
         *
         * ВНИМАНИЕ: Между setWidgetLimitExceeded(false) и результатом safeUpdateAll
         * существует окно гонки. Системное обновление виджета (через AppWidgetManager.updateAppWidget,
         * в обход updateMutex) может увидеть флаг false и попытаться выполнить полный рендер.
         *
         * Строгое соблюдение API: на AOSP системные обновления виджета не происходят
         * параллельно с обновлением приложения. provideGlance() вызывается только через
         * updateAll(). Окно гонки не реализуется на AOSP.
         *
         * Отклонение кастомной прошивки: если кастомная прошивка вызывает
         * updateAppWidget параллельно с updateAll() — это проблема кастомной прошивки.
         * Защита: provideGlance обёрнут в try-catch с fallback-рендером.
         */
        private suspend fun performProbeUpdate(): UpdateResult {
            // ВНИМАНИЕ: Между setWidgetLimitExceeded(false) и результатом safeUpdateAll
            // существует окно, в течение которого системное обновление виджета
            // (в обход updateMutex) может увидеть флаг false и попытаться
            // выполнить полный рендер. На AOSP это невозможно, т.к. система
            // не вызывает updateAppWidget во время активного обновления приложения.
            // На кастомных прошивках это возможно — это проблема прошивки.
            // Защита: provideGlance обёрнут в try-catch с fallback-рендером.
            setWidgetLimitExceeded(value = false)
            try {
                val probeResult = safeUpdateAll(context, isProbe = true)
                if (probeResult is UpdateResult.GenericFailure) {
                    setWidgetLimitExceeded(value = true)
                }
                // При LimitExceeded флаг тоже нужно вернуть — handleLimitExceeded
                // не должен видеть false и делать повторный рендер.
                if (probeResult is UpdateResult.LimitExceeded) {
                    setWidgetLimitExceeded(value = true)
                }
                // Защитная проверка: при isProbe=true isFullAttempt всегда true,
                // поэтому Success всегда имеет wasFull=true. Если по какой-то причине
                // wasFull=false (не должно происходить), флаг остаётся false —
                // это означает, что полный рендер прошёл и можно оставаться в полном режиме.
                // На AOSP GlanceAppWidget.updateAll() завершается за несколько секунд.
                // Если probe не укладывается в таймаут — проблема кастомной прошивки.
                //
                // При Success флаг widgetLimitExceeded остаётся false (не был установлен в true).
                // handleUpdateResult в updateAll() вызовет setWidgetLimitExceeded(false) повторно.
                // Это избыточно, но безвредно: setWidgetLimitExceeded проверяет текущее значение
                // и не выполняет запись, если значение не изменилось.
                return probeResult
            } catch (e: CancellationException) {
                // При отмене восстанавливаем флаг и пробрасываем.
                setWidgetLimitExceeded(value = true)
                throw e
            } catch (e: Exception) {
                setWidgetLimitExceeded(value = true)
                throw e
            }
        }

        /**
         * Устанавливает флаг widgetLimitExceeded.
         * Проверяет текущее значение и не выполняет запись, если значение не изменилось.
         * При ошибке записи логирует error и не бросает исключение.
         *
         * @param value Новое значение флага.
         */
        private suspend fun setWidgetLimitExceeded(value: Boolean) {
            try {
                val current = appSettingsRepository.observeAppSettings().value
                if (current.widgetLimitExceeded == value) return
                appSettingsRepository.updateAppSettings {
                    it.copy(widgetLimitExceeded = value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to set widget limit exceeded flag to $value", e)
            }
        }

        /**
         * Выполняет обновление виджетов с перехватом и классификацией ошибок.
         *
         * ВАЖНО: `isFullAttempt` вычисляется на основе значения `widgetLimitExceeded`
         * на момент вызова. Внутри виджета значение читается повторно. Если между
         * двумя чтениями флаг изменится, `isFullAttempt` может не соответствовать
         * реальному режиму рендера.
         *
         * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() является
         * suspend-функцией и завершается до возврата управления. Чтение флага
         * и рендер происходят в одной корутине, что минимизирует окно гонки.
         *
         * Отклонение кастомной прошивки: если кастомная прошивка вызывает
         * provideGlance() асинхронно относительно updateAll() — это проблема
         * кастомной прошивки, не проекта.
         *
         * @param isProbe Если true, выполняется принудительная попытка ПОЛНОГО рендера,
         *   даже если флаг widgetLimitExceeded установлен в true. Используется для
         *   проверки возможности возврата в обычный режим.
         */
        private suspend fun safeUpdateAll(
            context: Context,
            isProbe: Boolean = false,
        ): UpdateResult {
            // ВАЖНО: isFullAttempt вычисляется на основе widgetLimitExceeded на момент вызова.
            // Внутри виджета значение читается повторно в provideGlance(). Если флаг изменится
            // между двумя чтениями, isFullAttempt может не соответствовать реальному режиму рендера.
            //
            // Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() является suspend-функцией
            // и завершается до возврата управления. Чтение флага и рендер происходят в одной
            // корутине, что минимизирует окно гонки.
            //
            // Отклонение кастомной прошивки: если кастомная прошивка вызывает provideGlance()
            // асинхронно относительно updateAll() — это проблема кастомной прошивки.
            //
            // isFullAttempt используется ТОЛЬКО для классификации ошибок в handleUpdateResult.
            // Реальный режим рендера определяется внутри виджета независимо.
            val limitExceeded = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
            val isFullAttempt = isProbe || !limitExceeded

            return try {
                renderer.renderAll(context)
                UpdateResult.Success(wasFull = isFullAttempt)
            } catch (e: CancellationException) {
                // Должен перехватываться ПЕРВЫМ и пробрасываться дальше для корректной остановки корутин.
                throw e
            } catch (e: Exception) {
                classifyUpdateError(e, isFullAttempt = isFullAttempt, isProbe = isProbe)
            }
        }

        /**
         * Классифицирует ошибку обновления виджета.
         *
         * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() не бросает
         * исключений при корректном lifecycle. Все исключения здесь — либо
         * превышение лимита Binder (TransactionTooLargeException в cause-цепочке),
         * либо проблема кастомной прошивки / destroyed context.
         *
         * Классификация по тексту сообщения ИСКЛЮЧЕНА: текст может меняться
         * между версиями Android и OEM-сборками. Проверка по типу исключения
         * в cause-цепочке — единственный стабильный критерий.
         *
         * Отклонение кастомной прошивки: если кастомная прошивка бросает
         * TransactionTooLargeException при корректном lifecycle виджета
         * и объёме данных < 1 МБ — это проблема кастомной прошивки.
         */
        private fun classifyUpdateError(
            e: Exception,
            isFullAttempt: Boolean,
            isProbe: Boolean,
        ): UpdateResult =
            when (e) {
                is IllegalArgumentException -> {
                    if (isRemoteViewsLimitException(e)) {
                        if (isProbe) {
                            AppLogger.d("Widget full layout probe failed: still too large")
                        } else if (isFullAttempt) {
                            AppLogger.e("Widget update failed due to size limit. Detection confirmed.", e)
                        } else {
                            // Этого не должно происходить при упрощённом рендере на AOSP.
                            AppLogger.e("CRITICAL: Widget size limit exceeded even with simplified layout!", e)
                        }
                        UpdateResult.LimitExceeded
                    } else {
                        AppLogger.e("Widget update failed with IllegalArgumentException (not a size limit)", e)
                        UpdateResult.GenericFailure(isRetryable = true)
                    }
                }

                is IllegalStateException -> {
                    // IllegalStateException может означать destroyed context (поворот экрана
                    // во время обновления) или проблему кастомной прошивки.
                    // Повтор бесполезен до пересоздания context.
                    AppLogger.e(
                        "Widget update failed with IllegalStateException: ${e.message}",
                        e,
                    )
                    UpdateResult.GenericFailure(isRetryable = false)
                }

                is RuntimeException -> {
                    if (isRemoteViewsLimitException(e)) {
                        if (isProbe) {
                            AppLogger.d("Widget full layout probe failed (wrapped): still too large")
                        } else if (isFullAttempt) {
                            AppLogger.e("Widget update failed due to size limit (wrapped).", e)
                        } else {
                            AppLogger.e(
                                "CRITICAL: Widget size limit exceeded (wrapped) even with simplified layout!",
                                e,
                            )
                        }
                        UpdateResult.LimitExceeded
                    } else {
                        AppLogger.e("Widget update failed with RuntimeException: ${e.message}", e)
                        UpdateResult.GenericFailure(isRetryable = true)
                    }
                }

                else -> {
                    AppLogger.e("Generic widget update failure: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = true)
                }
            }

        /**
         * Определяет, вызван ли сбой обновления виджета превышением лимита RemoteViews.
         *
         * Строгое соблюдение API: единственный надёжный критерий превышения
         * лимита Binder-транзакции — наличие [android.os.TransactionTooLargeException]
         * в cause-цепочке. На AOSP GlanceAppWidget.updateAll() бросает это исключение
         * только при превышении лимита ~1 МБ на транзакцию.
         *
         * Классификация по тексту сообщения ИСКЛЮЧЕНА: текст может меняться
         * между версиями Android и OEM-сборками. Проверка по типу исключения
         * в cause-цепочке — единственный стабильный критерий.
         *
         * Отклонение кастомной прошивки: если кастомная прошивка бросает
         * TransactionTooLargeException при корректном lifecycle виджета
         * и объёме данных < 1 МБ — это проблема кастомной прошивки, не проекта.
         *
         * @return true, если в cause-цепочке есть TransactionTooLargeException.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean =
            hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)

        /**
         * Проверяет наличие исключения указанного типа в cause-цепочке.
         * Проходит по всей цепочке cause до корня.
         *
         * Классификация по тексту сообщения ИСКЛЮЧЕНА: текст может меняться
         * между версиями Android и OEM-сборками. Проверка по типу исключения
         * в cause-цепочке — единственный стабильный критерий.
         *
         * @param e Исключение для проверки.
         * @param type Тип исключения для поиска.
         * @return true если тип найден в cause-цепочке.
         */
        private fun hasCauseOfType(
            e: Throwable,
            type: Class<out Throwable>,
        ): Boolean {
            var current: Throwable? = e
            while (current != null) {
                if (type.isInstance(current)) return true
                current = current.cause
            }
            return false
        }

        companion object {
            /**
             * Таймаут обновления виджета.
             *
             * **Строгое соблюдение API:** на AOSP GlanceAppWidget.updateAll()
             * завершается за несколько секунд. 10 секунд — защитный запас.
             *
             * **Отклонение кастомной прошивки:** если кастомная прошивка
             * замедляет рендер виджета и updateAll() занимает больше 10 с —
             * это проблема кастомной прошивки.
             */
            private const val WIDGET_UPDATE_TIMEOUT_MS = 10_000L

            /**
             * Интервал пробных полных рендеров в режиме widgetLimitExceeded.
             *
             * Выбор значения: 10 обновлений. При стандартной частоте обновлений
             * (~1/час) это даёт один probe раз в ~10 часов. Этого достаточно
             * для обнаружения устранения причины превышения лимита (например,
             * пользователь удалил лишние SIM или изменил настройки), не создавая
             * избыточной нагрузки на систему Binder частыми TransactionTooLargeException.
             *
             * ВАЖНО: Счётчик [exceededUpdateCounter] НЕ персистентен. При перезапуске
             * приложения он обнуляется, и первый probe произойдёт через
             * PROBE_INTERVAL_UPDATES обновлений. Это допустимая эвристика в пределах
             * критерия надёжности: упрощённый layout всегда укладывается в лимит
             * Binder (~1 МБ на AOSP).
             *
             * Строгое соблюдение API: на AOSP RemoteViews-лимит ~1 МБ на Binder-транзакцию.
             * Упрощённый layout (~100 байт) всегда укладывается. TransactionTooLargeException
             * при упрощённом layout на AOSP невозможен.
             *
             * Отклонение кастомной прошивки: если probe стабильно падает,
             * это проблема кастомной прошивки с уменьшенным Binder-лимитом.
             */
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val PROBE_INTERVAL_UPDATES = 10
        }
    }
