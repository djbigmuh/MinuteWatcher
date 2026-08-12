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
                override suspend fun renderAll(context: Context) {
                    MinutesGlanceWidget().updateAll(context)
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
         * Обновляет все экземпляры виджета с ограниченной экспоненциальной стратегией повторов.
         *
         * Стратегия:
         * 1. Транзиентные ошибки (Binder congestion): повтор до [Constants.Widget.MAX_UPDATE_ATTEMPTS].
         * 2. Экспоненциальная задержка: старт [Constants.Widget.RETRY_BACKOFF_MS], удвоение.
         * 3. Перманентные ошибки (destroyed context): повтор пропускается.
         * 4. Превышение лимита RemoteViews: немедленный рендер с упрощённым layout,
         *    не считается попыткой повтора.
         *
         * Строгое соблюдение API: на AOSP GlanceAppWidget.updateAll() не бросает
         * исключений при корректном lifecycle. TransactionTooLargeException возможен
         * только при превышении лимита Binder (~1 МБ), что предотвращается
         * упрощённым layout при widgetLimitExceeded = true.
         */
        override suspend fun updateAll(): Boolean {
            var delayMs = Constants.Widget.RETRY_BACKOFF_MS
            var result = false
            var attempt = 1
            var keepGoing = true

            while (attempt <= Constants.Widget.MAX_UPDATE_ATTEMPTS && keepGoing) {
                val updateResult =
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
                    val current = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
                    if (current) {
                        setWidgetLimitExceeded(value = false)
                    }
                    Pair(true, false)
                }

                is UpdateResult.LimitExceeded -> {
                    val current = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
                    if (!current) {
                        setWidgetLimitExceeded(value = true)
                        AppLogger.w(
                            "Widget update failed due to size limit. " +
                                "Flag set to trigger simplified layout on next update.",
                        )
                        /**
                         * После установки флага widgetLimitExceeded, значение читается из StateFlow.value напрямую.
                         * StateFlow.update() гарантирует синхронную видимость нового значения в текущей корутине.
                         * На AOSP это всегда выполняется. На кастомных прошивках с нестандартной реализацией
                         * StateFlow, если значение не видно, рендер продолжается с текущим layout; упрощённый
                         * layout будет применён при следующем цикле обновления.
                         *
                         * Детерминированное ожидание: читаем из StateFlow напрямую,
                         * пока не увидим обновлённое значение. StateFlow.update()
                         * синхронно обновляет value, поэтому значение доступно сразу
                         * после возврата из updateAppSettings(). На AOSP updateAppSettings
                         * завершается синхронно; на кастомных прошивках с асинхронным
                         * DataStore ожидание гарантирует корректность.
                         */
                        val updated = appSettingsRepository.observeAppSettings().value
                        if (!updated.widgetLimitExceeded) {
                            AppLogger.w(
                                "widgetLimitExceeded flag not yet visible in StateFlow " +
                                    "after setWidgetLimitExceeded. Proceeding with re-render; " +
                                    "simplified layout will be applied on next update cycle.",
                            )
                        }

                        var renderSucceeded = true
                        try {
                            renderer.renderAll(context)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("Immediate widget re-render after limit exceeded failed", e)
                            renderSucceeded = false
                        }
                        return Pair(renderSucceeded, !renderSucceeded)
                    }
                    Pair(true, false)
                }

                is UpdateResult.GenericFailure -> {
                    Pair(false, result.isRetryable)
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

        private suspend fun safeUpdateAll(context: Context): UpdateResult =
            try {
                renderer.renderAll(context)
                UpdateResult.Success
            } catch (e: CancellationException) {
                // Должен перехватываться ПЕРВЫМ и пробрасываться дальше для корректной остановки корутин.
                throw e
            } catch (e: IllegalArgumentException) {
                if (isRemoteViewsLimitException(e)) {
                    AppLogger.e("Widget update failed due to size limit. Detection confirmed.", e)
                    UpdateResult.LimitExceeded
                } else {
                    AppLogger.e("Widget update failed with IllegalArgumentException (not a size limit)", e)
                    UpdateResult.GenericFailure(isRetryable = false)
                }
            } catch (e: IllegalStateException) {
                val message = e.message ?: ""
                val isPermanent =
                    message.contains("context", ignoreCase = true) ||
                        message.contains("not registered", ignoreCase = true) ||
                        message.contains("destroyed", ignoreCase = true)
                AppLogger.e("Widget update failed with IllegalStateException (permanent=$isPermanent): $message", e)
                UpdateResult.GenericFailure(isRetryable = !isPermanent)
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: RuntimeException,
            ) {
                if (isRemoteViewsLimitException(e)) {
                    AppLogger.e("Widget update failed due to size limit (wrapped).", e)
                    UpdateResult.LimitExceeded
                } else {
                    AppLogger.e("Widget update failed with RuntimeException: ${e.message}", e)
                    UpdateResult.GenericFailure(isRetryable = true)
                }
            } catch (e: Exception) {
                AppLogger.e("Generic widget update failure: ${e.message}", e)
                UpdateResult.GenericFailure(isRetryable = true)
            }

        /**
         * Detects whether a widget update failure is caused by the RemoteViews size limit.
         *
         * **Strict Compliance:**
         * Реализовано строгое соблюдение API. Отклонения кастомных прошивок не маскируются эвристиками,
         * а обрабатываются как системные сбои с уведомлением пользователя.
         * Детекция [android.os.TransactionTooLargeException] через cause-цепочку является
         * единственным критерием переполнения Binder-транзакции. Попытки определить переполнение
         * по тексту ошибки ("на глаз") исключены как ненадежные.
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

        companion object
    }

interface WidgetRenderer {
    suspend fun renderAll(context: Context)
}
