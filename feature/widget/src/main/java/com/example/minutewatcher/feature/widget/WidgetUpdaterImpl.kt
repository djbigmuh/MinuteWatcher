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
                ) {
                    MinutesGlanceWidget.setForceSimplified(context, forceSimplified)
                    try {
                        MinutesGlanceWidget().updateAll(context)
                    } finally {
                        MinutesGlanceWidget.setForceSimplified(context, false)
                    }
                }
            },
    ) : WidgetUpdater {
        private val updateMutex = Mutex()

        private sealed class UpdateResult {
            object Success : UpdateResult()

            object LimitExceeded : UpdateResult()

            data class GenericFailure(
                val isRetryable: Boolean,
            ) : UpdateResult()
        }

        /**
         * Обновляет все экземпляры виджета с ограниченной экспоненциальной
         * стратегией повторных попыток.
         *
         * Стратегия:
         * 1. Транзиентные ошибки (Binder congestion): повтор до
         *    [Constants.Widget.MAX_UPDATE_ATTEMPTS] с экспоненциальной задержкой,
         *    старт [Constants.Widget.RETRY_BACKOFF_MS], удвоение.
         * 2. Перманентные ошибки (destroyed context, not registered):
         *    повтор пропускается, возврат false.
         * 3. Превышение лимита RemoteViews (TransactionTooLargeException):
         *    немедленный рендер с упрощённым layout, не считается попыткой повтора.
         *    Флаг widgetLimitExceeded сохраняется в настройках.
         *
         * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() не бросает
         * исключений при корректном lifecycle. TransactionTooLargeException возможен
         * только при превышении лимита Binder (~1 МБ), что предотвращается
         * упрощённым layout при widgetLimitExceeded = true.
         * Если кастомная прошивка не соответствует документации Google —
         * это проблема кастомной прошивки.
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
                                safeUpdateAll(context)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Unexpected error updating widgets (attempt $attempt)", e)
                                UpdateResult.GenericFailure(isRetryable = true)
                            }
                        }
                    } ?: UpdateResult.GenericFailure(isRetryable = true) // Таймаут
                // Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() завершается
                // в пределах нескольких секунд. Таймаут 30 секунд — защитный механизм
                // от зависания на кастомных прошивках, не соответствующих документации Google.
                // Это проблема кастомной прошивки. Стратегия: считаем таймаут retryable-ошибкой,
                // чтобы следующая попытка могла succeed после восстановления системы.

                val (success, shouldRetry) = handleUpdateResult(updateResult)
                if (success) {
                    result = true
                    keepGoing = false
                } else if (!shouldRetry || attempt == Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                    keepGoing = false
                } else {
                    AppLogger.w("Widget update attempt $attempt failed, retrying in ${delayMs}ms")
                    delay(delayMs.milliseconds)
                    delayMs *= 2
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
                    // Если предыдущее обновление упало с LimitExceeded и флаг был установлен,
                    // но текущее обновление прошло успешно (упрощённый layout или данные уменьшились),
                    // сбрасываем флаг. Это позволяет следующему обновлению попытаться
                    // использовать полный layout. Осцилляция невозможна: если данные снова
                    // превысят лимит, updateAll упадёт и флаг будет установлен повторно.
                    val current = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
                    if (current) {
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
             * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() не бросает
             * TransactionTooLargeException при корректном lifecycle. Превышение лимита
             * Binder (~1 МБ) предотвращается упрощённым layout при widgetLimitExceeded.
             * Если кастомная прошивка не соответствует документации Google —
             * это проблема кастомной прошивки.
             */
            return try {
                renderer.renderAll(context, forceSimplified = true)
                Pair(true, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Re-render after limit exceeded failed, resetting flag", e)
                // Сбрасываем флаг, чтобы следующая попытка могла использовать полный layout.
                // Строгое соблюдение API: на AOSP упрощённый layout всегда умещается в лимит
                // Binder (~1 МБ). Сбой упрощённого рендера возможен только при деградации
                // системы на кастомных прошивках. Это проблема кастомной прошивки.
                // Стратегия: сбрасываем флаг и позволяем следующей попытке начать заново.
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
         * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() завершается
         * в пределах нескольких секунд. TransactionTooLargeException возможен только
         * при превышении лимита Binder (~1 МБ), что предотвращается упрощённым layout
         * при widgetLimitExceeded = true. Если кастомная прошивка не соответствует
         * документации Google — это проблема кастомной прошивки.
         *
         * Стратегия обработки исключений:
         * - [CancellationException]: пробрасывается немедленно для корректной остановки корутин.
         * - [IllegalArgumentException]: проверяется на превышение лимита RemoteViews. Если это
         *   не лимит, классифицируется как невосстанавливаемая ошибка.
         * - [IllegalStateException]: проверяется на "перманентность" (например, уничтоженный контекст).
         *   Перманентные ошибки не вызывают повторных попыток.
         * - [RuntimeException]: проверяется на обернутый [android.os.TransactionTooLargeException].
         * - [Exception]: общий перехват для защиты от нестабильных системных API на кастомных прошивках.
         */
        private suspend fun safeUpdateAll(context: Context): UpdateResult =
            try {
                renderer.renderAll(context, forceSimplified = false)
                UpdateResult.Success
            } catch (e: CancellationException) {
                // Должен перехватываться ПЕРВЫМ и пробрасываться дальше для корректной остановки корутин.
                throw e
            } catch (e: Exception) {
                classifyUpdateError(e)
            }

        /**
         * Классифицирует ошибку обновления виджетов для определения стратегии восстановления.
         */
        private fun classifyUpdateError(e: Exception): UpdateResult {
            val result =
                when (e) {
                    is IllegalArgumentException -> {
                        if (isRemoteViewsLimitException(e)) {
                            AppLogger.e("Widget update failed due to size limit. Detection confirmed.", e)
                            UpdateResult.LimitExceeded
                        } else {
                            AppLogger.e("Widget update failed with IllegalArgumentException (not a size limit)", e)
                            UpdateResult.GenericFailure(isRetryable = false)
                        }
                    }

                    is IllegalStateException -> {
                        val message = e.message ?: ""
                        val isPermanent =
                            message.contains("context", ignoreCase = true) ||
                                message.contains("not registered", ignoreCase = true) ||
                                message.contains("destroyed", ignoreCase = true)
                        AppLogger.e(
                            "Widget update failed with IllegalStateException (permanent=$isPermanent): $message",
                            e,
                        )
                        UpdateResult.GenericFailure(isRetryable = !isPermanent)
                    }

                    is RuntimeException -> {
                        if (isRemoteViewsLimitException(e)) {
                            AppLogger.e("Widget update failed due to size limit (wrapped).", e)
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
         * **Строгое соблюдение API:**
         * Единственный надёжный критерий переполнения Binder-транзакции — наличие
         * [android.os.TransactionTooLargeException] в cause-цепочке.
         * Попытки определить переполнение по тексту ошибки исключены как хрупкая эвристика.
         *
         * На AOSP GlanceAppWidget.updateAll() не бросает TransactionTooLargeException
         * при корректном lifecycle. Превышение лимита Binder (~1 МБ) предотвращается
         * упрощённым layout при widgetLimitExceeded = true.
         * Если кастомная прошивка не соответствует документации Google —
         * это проблема кастомной прошивки.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean {
            if (hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)) {
                AppLogger.e("RemoteViews size limit detected via TransactionTooLargeException", e)
                return true
            }

            return false
        }

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
        }
    }

interface WidgetRenderer {
    suspend fun renderAll(
        context: Context,
        forceSimplified: Boolean = false,
    )
}
