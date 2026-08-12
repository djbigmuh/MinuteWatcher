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
         * Updates all instances of the widget with a bounded exponential backoff retry strategy.
         *
         * **Strategy Details:**
         * 1. **Transient Errors**: If a failure is determined to be transient (e.g., temporary Binder
         *    congestion), the method retries up to [Constants.Widget.MAX_UPDATE_ATTEMPTS] times.
         * 2. **Exponential Backoff**: Delay starts at [Constants.Widget.RETRY_BACKOFF_MS] and
         *    doubles with each attempt.
         * 3. **Permanent Errors**: If the error is permanent (e.g., [IllegalStateException] with
         *    an invalid context or destroyed widget message), retry is skipped to save resources.
         * 4. **Size Limits**: If [UpdateResult.LimitExceeded] is detected, the widget is immediately re-rendered
         *    using a simplified layout; this does not count as a retry attempt in this loop.
         *
         * @return true if widgets were successfully updated (either directly or via simplified fallback).
         */
        override suspend fun updateAll(): Boolean {
            var delayMs = Constants.Widget.RETRY_BACKOFF_MS
            var isSuccessful = false
            var attempt = 1
            var shouldRetryLoop = true

            while ((attempt <= Constants.Widget.MAX_UPDATE_ATTEMPTS) && shouldRetryLoop) {
                val updateResult =
                    updateMutex.withLock {
                        try {
                            safeUpdateAll(context)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("Unexpected error updating widgets in UpdateCoordinator loop", e)
                            UpdateResult.GenericFailure(isRetryable = true)
                        }
                    }

                val (success, shouldRetryResult) = handleUpdateResult(updateResult)

                if (success) {
                    isSuccessful = true
                    shouldRetryLoop = false
                } else if (!shouldRetryResult) {
                    shouldRetryLoop = false
                } else if (attempt < Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                    AppLogger.w("Widget update attempt $attempt failed, retrying in ${delayMs}ms")
                    delay(delayMs.milliseconds)
                    delayMs *= 2
                    attempt++
                } else {
                    shouldRetryLoop = false
                    attempt++ // Count the final failed attempt
                }
            }
            if (!isSuccessful) {
                val totalAttempts = minOf(attempt, Constants.Widget.MAX_UPDATE_ATTEMPTS)
                AppLogger.e("Widget update failed after $totalAttempts attempts")
            }
            return isSuccessful
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
         * **Contract (AOSP):**
         * Android enforces a strict size limit on Binder transactions (typically 1MB).
         * Since RemoteViews are sent via Binder, exceeding this limit results in a
         * [android.os.TransactionTooLargeException]. In AOSP, this exception is guaranteed
         * to be present in the causal chain when a widget is too complex to render.
         *
         * **Heuristic/Fallback:**
         * The method traverses the exception's `cause` chain searching for [android.os.TransactionTooLargeException].
         *
         * **Boundaries:**
         * This detection is reliable across all supported Android versions (API 26–35).
         * If a custom firmware wraps this error in a non-standard way or uses a different
         * mechanism to signal size limits, it is considered a firmware-level deviation
         * that the app cannot reliably detect.
         *
         * **Reliability Criterion:**
         * Checking the entire cause chain for [android.os.TransactionTooLargeException] is
         * the most robust way to identify binder overflow, as it avoids relying on
         * platform-specific wrapper exception types (like `RemoteViews$ActionException`)
         * which can vary between OS versions.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean {
            // Основной критерий: TransactionTooLargeException в cause-цепочке.
            // Это единственный достоверный признак переполнения Binder-транзакции,
            // гарантированный на всех версиях Android API 26–37.
            if (hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)) {
                AppLogger.w("RemoteViews size limit detected via TransactionTooLargeException", e)
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
