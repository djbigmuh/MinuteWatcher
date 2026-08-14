package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.glance.appwidget.updateAll
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class WidgetUpdaterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val renderer: WidgetRenderer =
            object : WidgetRenderer {
                override suspend fun renderAll(
                    context: Context,
                    forceSimplified: Boolean,
                    forceFull: Boolean,
                ) {
                    MinutesGlanceWidget.setForceSimplified(context, forceSimplified)
                    MinutesGlanceWidget.setForceFull(context, forceFull)
                    try {
                        MinutesGlanceWidget().updateAll(context)
                    } finally {
                        MinutesGlanceWidget.setForceSimplified(context, false)
                        MinutesGlanceWidget.setForceFull(context, false)
                    }
                }
            },
    ) : WidgetUpdater {
        private val updateMutex = Mutex()

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
         * Обновляет все экземпляры виджета с ограниченной экспоненциальной
         * стратегией повторных попыток.
         *
         * **Контракт AOSP:** на AOSP GlanceAppWidget.updateAll() не бросает исключений при корректном lifecycle.
         * TransactionTooLargeException возможен только при превышении лимита Binder (~1 МБ).
         *
         * **Отклонение кастомной прошивки:** Если кастомная прошивка не соответствует документации Google —
         * это проблема кастомной прошивки.
         *
         * **Стратегия приложения:**
         * 1. Транзиентные ошибки: повтор до [Constants.Widget.MAX_UPDATE_ATTEMPTS] с экспоненциальной задержкой.
         * 2. Перманентные ошибки: повтор пропускается.
         * 3. Превышение лимита RemoteViews: немедленный рендер с упрощённым layout.
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
                                if (settings.widgetLimitExceeded &&
                                    (updateCounter.incrementAndGet() % PROBE_INTERVAL_UPDATES == 0)
                                ) {
                                    // Пробуем полный рендер. Если он удастся, handleUpdateResult сбросит флаг.
                                    val probeResult = safeUpdateAll(context, forceFull = true)
                                    if (probeResult is UpdateResult.Success) {
                                        probeResult
                                    } else {
                                        // Проба не удалась — выполняем штатный упрощённый рендер.
                                        safeUpdateAll(context, forceFull = false)
                                    }
                                } else {
                                    safeUpdateAll(context, forceFull = false)
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
                renderer.renderAll(context, forceSimplified = true, forceFull = false)
                Pair(true, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Re-render after limit exceeded failed, resetting flag", e)
                // **Контракт AOSP:** на AOSP упрощённый layout всегда умещается в лимит Binder (~1 МБ).
                //
                // **Отклонение кастомной прошивки:** Сбой упрощённого рендера возможен только при
                // деградации системы на кастомных прошивках. Это проблема кастомной прошивки.
                //
                // **Стратегия приложения:** сбрасываем флаг и позволяем следующей попытке начать заново.
                setWidgetLimitExceeded(value = false)
                Pair(false, true) // isRetryable = true
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
            forceFull: Boolean = false,
        ): UpdateResult {
            val limitExceeded = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
            val isFullAttempt = forceFull || !limitExceeded

            return try {
                renderer.renderAll(context, forceSimplified = false, forceFull = forceFull)
                UpdateResult.Success(wasFull = isFullAttempt)
            } catch (e: CancellationException) {
                // Должен перехватываться ПЕРВЫМ и пробрасываться дальше для корректной остановки корутин.
                throw e
            } catch (e: Exception) {
                classifyUpdateError(e, isFullAttempt = isFullAttempt, isProbe = forceFull)
            }
        }

        /**
         * Классифицирует ошибку обновления виджетов для определения стратегии восстановления.
         */
        private fun classifyUpdateError(
            e: Exception,
            isFullAttempt: Boolean,
            isProbe: Boolean,
        ): UpdateResult {
            val result =
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
                            UpdateResult.GenericFailure(isRetryable = false)
                        }
                    }

                    is IllegalStateException -> {
                        // **Контракт AOSP:** на AOSP IllegalStateException сигнализирует о некорректном состоянии.
                        //
                        // **Отклонение кастомной прошивки:** Классификация по тексту сообщения исключена.
                        // Если состояние системы перманентно нарушено на кастомной прошивке —
                        // это проблема кастомной прошивки.
                        //
                        // **Стратегия приложения:** Все IllegalStateException считаются retryable.
                        AppLogger.e("Widget update failed with IllegalStateException: ${e.message}", e)
                        UpdateResult.GenericFailure(isRetryable = true)
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
                        // Широкий catch оставлен намеренно: защита от падений системных API на кастомных прошивках,
                        // не соответствующих документации Google. Это проблема кастомной прошивки, а не штатного пути.
                        AppLogger.e("Generic widget update failure: ${e.message}", e)
                        UpdateResult.GenericFailure(isRetryable = true)
                    }
                }
            return result
        }

        /**
         * Детектирует, вызван ли сбой обновления виджета превышением лимита RemoteViews.
         *
         * **Контракт AOSP:** Единственный надёжный критерий переполнения Binder-транзакции — наличие
         * [android.os.TransactionTooLargeException] в cause-цепочке.
         *
         * **Отклонение кастомной прошивки:** Попытки определить переполнение по тексту ошибки исключены.
         * Если прошивка бросает TransactionTooLargeException — это проблема кастомной прошивки.
         *
         * **Стратегия приложения:** Переход на упрощённый layout при детекции.
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
            private const val WIDGET_UPDATE_TIMEOUT_MS = 30_000L
            private const val PROBE_INTERVAL_UPDATES = 10

            /**
             * Счетчик обновлений для периодической проверки ("пробы") полного рендера.
             * Используется AtomicInteger, так как updateAll() может вызываться конкурентно
             * из разных корутин (например, UpdateCoordinator и WidgetSettingsSaver).
             */
            @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
            internal val updateCounter = AtomicInteger(0)
        }
    }

interface WidgetRenderer {
    suspend fun renderAll(
        context: Context,
        forceSimplified: Boolean = false,
        forceFull: Boolean = false,
    )
}
