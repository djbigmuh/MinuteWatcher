package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
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

@Singleton
class WidgetUpdaterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val renderer: WidgetRenderer,
    ) : WidgetUpdater {
        private val updateMutex = Mutex()

        /**
         * Счётчик обновлений, выполненных в режиме widgetLimitExceeded.
         * Каждое [PROBE_INTERVAL_UPDATES]-е обновление выполняет пробный полный рендер.
         *
         * **Не персистится.** При рестарте приложения сбрасывается в 0.
         * Это допустимо: при входе в режим exceeded первый probe произойдёт
         * через [PROBE_INTERVAL_UPDATES] обновлений (~10 часов при ~1 обновлении/час).
         *
         * **Эвристика в пределах критерия надёжности:** интервал 10 обновлений
         * выбран как баланс между частотой попыток восстановления полного рендера
         * и нагрузкой на систему. На AOSP RemoteViews-лимит ~1 МБ;
         * упрощённый layout всегда укладывается. Выход за лимит при упрощённом
         * layout — проблема кастомной прошивки.
         *
         * Доступ только внутри updateMutex.withLock — атомарность не требуется.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal var exceededUpdateCounter = 0L

        private sealed class UpdateResult {
            data class Success(
                val wasFull: Boolean,
            ) : UpdateResult()

            object LimitExceeded : UpdateResult()

            data class GenericFailure(
                val isRetryable: Boolean,
            ) : UpdateResult()
        }

        // Sealed class вместо Pair<Boolean, Boolean> исключает хрупкую эвристику
        // перепутывания порядка булевых значений. Компилятор гарантирует
        // исчерпывающую обработку всех вариантов.
        private sealed class UpdateOutcome {
            data class Success(
                val shouldStop: Boolean = true,
            ) : UpdateOutcome()

            data class Retry(
                val delayMs: Long,
            ) : UpdateOutcome()

            object Stop : UpdateOutcome()
        }

        /**
         * Обновляет все экземпляры виджета с ограниченной экспоненциальной стратегией повторных попыток.
         *
         * **Стратегия повторных попыток:**
         * 1. Транзиентные ошибки (Binder congestion, IllegalStateException): повтор до
         *    [Constants.Widget.MAX_UPDATE_ATTEMPTS] с экспоненциальной задержкой.
         *    Старт: [Constants.Widget.RETRY_BACKOFF_MS], удвоение на каждой попытке,
         *    потолок: [Constants.Widget.MAX_RETRY_DELAY_MS].
         * 2. Перманентные ошибки (destroyed context, не-RemoteViews IllegalArgumentException):
         *    повтор НЕ выполняется.
         * 3. Превышение лимита RemoteViews (TransactionTooLargeException в cause-цепочке):
         *    немедленный рендер с упрощённым layout, НЕ считается попыткой повтора.
         *
         * **Контракт AOSP:** На AOSP GlanceAppWidget.updateAll() не бросает исключений
         * при корректном lifecycle. TransactionTooLargeException возможен только при
         * превышении лимита Binder (~1 МБ).
         *
         * **Отклонение кастомной прошивки:** Если кастомная прошивка бросает исключения
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
         * **Строгое соблюдение API:** на AOSP GlanceAppWidget.updateAll() завершается
         * за несколько секунд. Таймаут 10с — защитный механизм.
         */
        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome =
            updateMutex.withLock {
                val updateOutcome =
                    withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        val updateResult =
                            try {
                                val settings = appSettingsRepository.observeAppSettings().value
                                if (settings.widgetLimitExceeded) {
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

        private suspend fun handleUpdateResult(result: UpdateResult): UpdateOutcome =
            when (result) {
                is UpdateResult.Success -> {
                    // Сбрасываем флаг только если реально был предпринят и удался ПОЛНЫЙ рендер.
                    // Если рендер был упрощённым (из-за установленного флага или принудительно),
                    // успех не означает, что полный рендер теперь пройдёт.
                    // Это предотвращает осцилляцию флага и лишние TransactionTooLargeException.
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
                        UpdateOutcome.Retry(0L) // delay рассчитывается снаружи
                    } else {
                        UpdateOutcome.Stop
                    }
                }
            }

        /**
         * Обработка превышения лимита RemoteViews (TransactionTooLargeException).
         *
         * **Строгое соблюдение API:** на AOSP GlanceAppWidget.updateAll() бросает
         * TransactionTooLargeException только при превышении лимита Binder (~1 МБ).
         * Упрощённый layout (widgetLimitExceeded = true) всегда укладывается в этот лимит.
         * Если упрощённый рендер тоже падает — это проблема кастомной прошивки
         * или destroyed context, а не превышение лимита.
         *
         * Стратегия: при падении упрощённого рендера флаг НЕ сбрасывается,
         * повтор не выполняется. Это предотвращает осцилляцию флага.
         */
        private suspend fun handleLimitExceeded(): UpdateOutcome {
            val current = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
            if (current) {
                // Флаг уже установлен (например, probe восстановил его).
                // Повторный рендер не нужен — предотвращаем осцилляцию.
                return UpdateOutcome.Stop
            }

            setWidgetLimitExceeded(value = true)
            AppLogger.w("Widget update failed due to size limit. Rendering with simplified layout.")

            /**
             * **Контракт AOSP:** на AOSP GlanceAppWidget.updateAll() не бросает
             * TransactionTooLargeException при корректном lifecycle.
             *
             * **Отклонение кастомной прошивки:** Если кастомная прошивка не соответствует документации Google —
             * это проблема кастомной прошивки.
             *
             * **Стратегия приложения:** Превышение лимита Binder (~1 МБ) предотвращается упрощённым layout.
             */
            return try {
                renderer.renderAll(context)
                UpdateOutcome.Success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Дифференцируем тип ошибки для диагностики:
                // - TransactionTooLargeException в cause-цепочке: даже упрощённый layout
                //   не укладывается в лимит Binder. На AOSP это невозможно для упрощённого
                //   layout (~100 байт). Если происходит — проблема кастомной прошивки.
                // - Иные исключения: destroyed context, SecurityException и т.п.
                //   Повтор бессмысленен до устранения причины.
                val isStillSizeLimit = isRemoteViewsLimitException(e)
                if (isStillSizeLimit) {
                    AppLogger.e(
                        "CRITICAL: Simplified layout also exceeds Binder limit. " +
                            "This should not happen on AOSP. Possible custom firmware issue.",
                        e,
                    )
                } else {
                    AppLogger.e("Re-render after limit exceeded failed (non-size error)", e)
                }
                // Флаг уже установлен в true выше. Повторный вызов не нужен.
                // На AOSP упрощённый layout всегда укладывается в лимит Binder.
                // Если он тоже падает — это проблема кастомной прошивки.
                UpdateOutcome.Stop // isRetryable = false — осцилляция бессмысленна
            }
        }

        /**
         * Пробный полный рендер для проверки, устранена ли причина превышения лимита.
         *
         * **Последовательность (внутри updateMutex.withLock):**
         * 1. Сброс widgetLimitExceeded = false для полного рендера.
         * 2. Вызов safeUpdateAll(isProbe = true).
         * 3. При GenericFailure или LimitExceeded: возврат флага = true вручную
         *    для предотвращения осцилляции и повторного рендера.
         * 4. При CancellationException или иных исключениях: гарантированное восстановление
         *    флага = true через try-catch блоки.
         * 5. При Success: handleUpdateResult подтвердит успех (wasFull = true).
         *
         * **Зачем try/catch:** если во время probe сработает таймаут (CancellationException)
         * или произойдёт сбой, флаг должен вернуться в true. Иначе следующая попытка
         * обновления снова начнётся с полного рендера, вызывая бесконечный цикл "полный -> таймаут -> полный".
         *
         * **Строгое соблюдение API:** на AOSP RemoteViews-лимит составляет ~1 МБ на Binder-транзакцию.
         * Упрощённый layout всегда укладывается в этот лимит; выход за него при упрощённом layout —
         * признак проблемы кастомной прошивки. GlanceAppWidget.updateAll() на AOSP не бросает
         * TransactionTooLargeException при корректном lifecycle и объёме < 1 МБ.
         * Если probe падает — это проблема кастомной прошивки или destroyed context.
         *
         * **Потокобезопасность:** метод вызывается только внутри updateMutex.withLock,
         * что гарантирует отсутствие конкурентного доступа к widgetLimitExceeded
         * между установкой false и обработкой результата.
         */
        private suspend fun performProbeUpdate(): UpdateResult {
            // Доступ только внутри updateMutex.withLock (вызывается из performSingleUpdate)
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
         * **Семантика параметров:**
         * @param isProbe Если true, выполняется принудительная попытка ПОЛНОГО рендера,
         *   даже если флаг widgetLimitExceeded установлен в true. Используется для
         *   проверки возможности возврата в обычный режим.
         *
         * **Логика isFullAttempt:**
         * Определяет, считается ли текущий рендер "полным" для целей сброса флага.
         * Рендер считается полным, если (isProbe == true) ИЛИ (widgetLimitExceeded == false).
         *
         * **Контракт AOSP:** на AOSP GlanceAppWidget.updateAll() завершается в пределах нескольких секунд.
         * TransactionTooLargeException возможен только при превышении лимита Binder (~1 МБ).
         *
         * **Отклонение кастомной прошивки:** Если кастомная прошивка не соответствует документации Google —
         * это проблема кастомной прошивки.
         *
         * **Стратегия приложения:** Превышение лимита предотвращается упрощённым layout при
         * widgetLimitExceeded = true.
         */
        private suspend fun safeUpdateAll(
            context: Context,
            isProbe: Boolean = false,
        ): UpdateResult {
            // Режим рендера (упрощённый / полный) определяется внутри
            // MinutesGlanceWidget.provideGlance на основе WidgetSettingsState.widgetLimitExceeded.
            // Внешние флаги не передаются, что исключает race condition.
            //
            // Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() является
            // suspend-функцией и завершается до возврата управления.
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
         * **Строгое соблюдение API:** на AOSP GlanceAppWidget.updateAll()
         * не бросает исключений при корректном lifecycle. Все исключения
         * здесь — либо превышение лимита Binder (TransactionTooLargeException),
         * либо проблема кастомной прошивки / destroyed context.
         *
         * Классификация:
         * - TransactionTooLargeException в cause → LimitExceeded
         *   (переход на упрощённый layout)
         * - IllegalArgumentException без TransactionTooLargeException
         *   → GenericFailure
         * - IllegalStateException → GenericFailure (destroyed context)
         * - RuntimeException с TransactionTooLargeException → LimitExceeded
         * - Иные → GenericFailure
         *
         * **Классификация по тексту сообщения ИСКЛЮЧЕНА:** текст может
         * меняться между версиями Android и OEM-сборками. Проверка по типу
         * исключения в cause-цепочке — единственный стабильный критерий.
         *
         * **Отклонение кастомной прошивки:** если кастомная прошивка бросает
         * TransactionTooLargeException при корректном lifecycle виджета
         * и объёме данных < 1 МБ — это проблема кастомной прошивки.
         */
        private fun classifyUpdateError(
            e: Exception,
            isFullAttempt: Boolean,
            isProbe: Boolean,
        ): UpdateResult {
            val isDestroyed = e.message?.contains("destroyed", ignoreCase = true) == true

            return when (e) {
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
                        UpdateResult.GenericFailure(isRetryable = !isDestroyed)
                    }
                }

                is IllegalStateException -> {
                    // Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll()
                    // не бросает IllegalStateException при корректном lifecycle.
                    // Исключение возможно только при destroyed context (поворот экрана
                    // во время обновления) или на кастомной прошивке.
                    // Повтор при destroyed context бесполезен — нужен новый context.

                    // IllegalStateException с "destroyed" в сообщении — destroyed context.
                    // Повтор бесполезен до пересоздания context.
                    // Другие IllegalStateException (например, Glance не инициализирован)
                    // могут быть retryable.
                    val isDestroyedContext = e.message?.contains("destroyed", ignoreCase = true) == true
                    AppLogger.e(
                        "Widget update failed with IllegalStateException " +
                            "(destroyed=$isDestroyedContext): ${e.message}",
                        e,
                    )
                    UpdateResult.GenericFailure(isRetryable = !isDestroyedContext)
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
                        UpdateResult.GenericFailure(isRetryable = !isDestroyed)
                    }
                }

                else -> {
                    AppLogger.e("Generic widget update failure: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = !isDestroyed)
                }
            }
        }

        /**
         * Определяет, вызван ли сбой обновления виджета превышением лимита RemoteViews.
         *
         * **Строгое соблюдение API:** единственный надёжный критерий превышения
         * лимита Binder-транзакции — наличие [android.os.TransactionTooLargeException]
         * в cause-цепочке. На AOSP GlanceAppWidget.updateAll() бросает это исключение
         * только при превышении лимита ~1 МБ на транзакцию.
         *
         * **Классификация по тексту сообщения ИСКЛЮЧЕНА:** текст может меняться
         * между версиями Android и OEM-сборками. Проверка по типу исключения
         * в cause-цепочке — единственный стабильный критерий.
         *
         * **Отклонение кастомной прошивки:** если кастомная прошивка бросает
         * TransactionTooLargeException при корректном lifecycle виджета
         * и объёме данных < 1 МБ — это проблема кастомной прошивки, не проекта.
         *
         * @return true, если в cause-цепочке есть TransactionTooLargeException.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean =
            hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)

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
             * **Выбор значения:** 10 обновлений.
             * При стандартной частоте обновлений (~1/час) это даёт один probe раз в 10 часов.
             * Этого достаточно для обнаружения того, что причина превышения лимита устранена
             * (например, пользователь удалил лишние SIM или изменил настройки), не создавая
             * избыточной нагрузки на систему Binder частыми TransactionTooLargeException.
             *
             * **Эвристика в пределах критерия надёжности:**
             * Счётчик [exceededUpdateCounter] не персистится. При рестарте
             * приложения он обнуляется, и первый probe произойдёт через
             * PROBE_INTERVAL_UPDATES обновлений. При ~1 обновлении/час
             * probe выполняется раз в ~10 часов. Это допустимо: на AOSP
             * RemoteViews-лимит ~1 МБ на Binder-транзакцию, и упрощённый
             * layout всегда укладывается в этот лимит. Если probe падает —
             * проблема кастомной прошивки с уменьшенным Binder-лимитом.
             *
             * **Строгое соблюдение API:** на AOSP RemoteViews-лимит
             * составляет ~1 МБ. Упрощённый layout (~100 байт) всегда
             * укладывается. TransactionTooLargeException при упрощённом
             * layout на AOSP невозможен.
             */
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal const val PROBE_INTERVAL_UPDATES = 10
        }
    }
