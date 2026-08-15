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
         * Каждое PROBE_INTERVAL_UPDATES-е обновление в этом режиме выполняет
         * пробный полный рендер для проверки, не устранена ли причина превышения.
         *
         * Доступ только внутри updateMutex.withLock — атомарность не требуется.
         */
        private var exceededUpdateCounter = 0L

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
         */
        override suspend fun updateAll(): Boolean {
            var delayMs = Constants.Widget.RETRY_BACKOFF_MS
            var result = false
            var attempt = 1
            var keepGoing = true

            while (attempt <= Constants.Widget.MAX_UPDATE_ATTEMPTS && keepGoing) {
                // Стратегия повторных попыток:
                // 1. Транзиентные ошибки (Binder congestion): повтор до MAX_UPDATE_ATTEMPTS
                // 2. Экспоненциальная задержка: старт RETRY_BACKOFF_MS, удвоение
                // 3. Перманентные ошибки (destroyed context): повтор пропускается
                // 4. Превышение лимита RemoteViews: немедленный рендер с упрощённым layout,
                //    не считается попыткой повтора
                val updateResult =
                    withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        updateMutex.withLock {
                            try {
                                val settings = appSettingsRepository.observeAppSettings().value
                                if (settings.widgetLimitExceeded) {
                                    val count = ++exceededUpdateCounter
                                    if (count % PROBE_INTERVAL_UPDATES == 0L) {
                                        // Стратегия восстановления после превышения лимита RemoteViews:
                                        //
                                        // При установленном widgetLimitExceeded рендер выполняется в упрощённом режиме.
                                        // Каждое PROBE_INTERVAL_UPDATES-е обновление пытается выполнить ПОЛНЫЙ рендер
                                        // для проверки, не устранена ли причина превышения
                                        // (например, пользователь удалил SIM-карты и объём данных уменьшился).
                                        //
                                        // Если проба успешна — handleUpdateResult сбросит widgetLimitExceeded.
                                        // Если проба неудачна (LimitExceeded) — handleLimitExceeded восстановит флаг.
                                        // Если проба неудачна (GenericFailure) — восстанавливаем флаг вручную.
                                        //
                                        // **Контракт AOSP:** На AOSP RemoteViews лимит ~1 МБ на Binder-транзакцию.
                                        // Упрощённый layout всегда укладывается в этот лимит.
                                        //
                                        // **Отклонение кастомной прошивки:** Если кастомная прошивка имеет
                                        // уменьшенный лимит Binder — это проблема кастомной прошивки.
                                        // Упрощённый layout является защитным механизмом.
                                        setWidgetLimitExceeded(value = false)
                                        val probeResult = safeUpdateAll(context, isProbe = true)
                                        if (probeResult is UpdateResult.GenericFailure) {
                                            setWidgetLimitExceeded(value = true)
                                        }
                                        probeResult
                                    } else {
                                        safeUpdateAll(context, isProbe = false)
                                    }
                                } else {
                                    exceededUpdateCounter = 0L // сброс при выходе из режима
                                    safeUpdateAll(context, isProbe = false)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Unexpected error updating widgets (attempt $attempt)", e)
                                UpdateResult.GenericFailure(isRetryable = true)
                            }
                        }
                    } ?: UpdateResult.GenericFailure(isRetryable = true) // Таймаут
                // **Контракт AOSP:** на AOSP GlanceAppWidget.updateAll() завершается в пределах нескольких секунд.
                //
                // **Отклонение кастомной прошивки:** Зависание на кастомных прошивках. Это проблема кастомной прошивки.
                //
                // **Стратегия приложения:** Таймаут 30 секунд — защитный механизм; считается retryable-ошибкой.

                val (success, shouldRetry) = handleUpdateResult(updateResult)
                if (success) {
                    result = true
                    keepGoing = false
                } else if (!shouldRetry || attempt == Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                    keepGoing = false
                } else {
                    AppLogger.w("Widget update attempt $attempt failed, retrying in ${delayMs}ms")
                    delay(delayMs.milliseconds)
                    delayMs = (delayMs * 2).coerceAtMost(Constants.Widget.MAX_RETRY_DELAY_MS)
                    attempt++
                }
            }

            if (!result) {
                val total = minOf(attempt, Constants.Widget.MAX_UPDATE_ATTEMPTS)
                AppLogger.e("Widget update failed after $total attempts")
            }
            return result
        }

        private suspend fun handleUpdateResult(result: UpdateResult): Pair<Boolean, Boolean> =
            when (result) {
                is UpdateResult.Success -> {
                    // Сбрасываем флаг только если реально был предпринят и удался ПОЛНЫЙ рендер.
                    // Если рендер был упрощённым (из-за установленного флага или принудительно),
                    // успех не означает, что полный рендер теперь пройдёт.
                    // Это предотвращает осцилляцию флага и лишние TransactionTooLargeException.
                    if (result.wasFull) {
                        setWidgetLimitExceeded(value = false)
                    }
                    Pair(true, false)
                }

                is UpdateResult.LimitExceeded -> {
                    handleLimitExceeded()
                }

                is UpdateResult.GenericFailure -> {
                    Pair(false, result.isRetryable)
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
        private suspend fun handleLimitExceeded(): Pair<Boolean, Boolean> {
            val current = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
            if (current) {
                // Повторные попытки после уже установленного флага не имеют смысла.
                return Pair(false, false)
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
                Pair(true, false)
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
                setWidgetLimitExceeded(value = true)
                Pair(false, false) // isRetryable = false — осцилляция бессмысленна
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
         * не бросает исключений при корректном lifecycle. Все исключения здесь —
         * либо превышение лимита Binder (TransactionTooLargeException),
         * либо проблема кастомной прошивки / destroyed context.
         *
         * Классификация:
         * - TransactionTooLargeException в cause → LimitExceeded (переход на упрощённый layout)
         * - IllegalArgumentException без TransactionTooLargeException → GenericFailure
         * - IllegalStateException → GenericFailure (destroyed context)
         * - RuntimeException с TransactionTooLargeException → LimitExceeded
         * - Иные → GenericFailure
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
                    AppLogger.e("Widget update failed with IllegalStateException: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = !isDestroyed)
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
         * между версиями Android и OEM-сборками. Если кастомная прошивка бросает
         * TransactionTooLargeException при корректном lifecycle виджета —
         * это проблема кастомной прошивки.
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
             * **Контракт AOSP:** на AOSP GlanceAppWidget.updateAll() завершается
             * в пределах нескольких секунд. 10 секунд — защитный запас.
             *
             * **Отклонение кастомной прошивки:** зависание на кастомных прошивках.
             * Это проблема кастомной прошивки.
             *
             * **Стратегия приложения:** таймаут считается retryable-ошибкой.
             */
            private const val WIDGET_UPDATE_TIMEOUT_MS = 10_000L

            /**
             * Интервал пробных полных рендеров в режиме widgetLimitExceeded.
             *
             * Каждое PROBE_INTERVAL_UPDATES-е обновление выполняет пробный полный рендер
             * для проверки, не устранена ли причина превышения
             * (например, пользователь удалил SIM-карты и объём данных уменьшился).
             *
             * **Строгое соблюдение API:** на AOSP RemoteViews-лимит ~1 МБ
             * на Binder-транзакцию. Упрощённый layout всегда укладывается в лимит.
             *
             * **Отклонение кастомной прошивки:** если кастомная прошивка имеет
             * уменьшенный лимит Binder — это проблема кастомной прошивки.
             *
             * Значение 10 выбрано как баланс между частотой проб и нагрузкой:
             * при ~1 обновлении в час проба происходит раз в ~10 часов.
             */
            private const val PROBE_INTERVAL_UPDATES = 10
        }
    }
