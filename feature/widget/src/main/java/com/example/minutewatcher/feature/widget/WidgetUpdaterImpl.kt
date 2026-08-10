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
    ) : WidgetUpdater {
        private val widget = MinutesGlanceWidget()
        private val updateMutex = Mutex()

        private sealed class UpdateResult {
            object Success : UpdateResult()

            object LimitExceeded : UpdateResult()

            object GenericFailure : UpdateResult()
        }

        override suspend fun updateAll() {
            updateMutex.withLock {
                // Читаем текущее состояние флага внутри мьютекса для предотвращения гонки.
                val wasExceeded = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
                try {
                    val result = attemptUpdateWithRetry(context)
                    when (result) {
                        is UpdateResult.Success -> {
                            if (wasExceeded) {
                                setWidgetLimitExceeded(false)
                            }
                        }

                        is UpdateResult.LimitExceeded -> {
                            if (!wasExceeded) {
                                setWidgetLimitExceeded(true)
                                AppLogger.w(
                                    "Widget update failed due to size limit. " +
                                        "Flag set to trigger simplified layout on next update.",
                                )
                            }
                        }

                        is UpdateResult.GenericFailure -> {
                            AppLogger.e("Widget update failed after ${Constants.Widget.MAX_UPDATE_ATTEMPTS} attempts")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Unexpected error updating widgets", e)
                }
            }
        }

        private suspend fun attemptUpdateWithRetry(context: Context): UpdateResult {
            var delayMs = Constants.Widget.RETRY_BACKOFF_MS
            var lastResult: UpdateResult = UpdateResult.GenericFailure
            for (attempt in 1..Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                lastResult = safeUpdateAll(context)
                if (lastResult is UpdateResult.Success || lastResult is UpdateResult.LimitExceeded) {
                    return lastResult
                }
                if (attempt < Constants.Widget.MAX_UPDATE_ATTEMPTS) {
                    AppLogger.w("Widget update attempt $attempt failed, retrying in ${delayMs}ms")
                    delay(delayMs.milliseconds)
                    delayMs *= 2
                }
            }
            return lastResult
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
                widget.updateAll(context)
                UpdateResult.Success
            } catch (e: CancellationException) {
                // Должен перехватываться ПЕРВЫМ и пробрасываться дальше для корректной остановки корутин.
                throw e
            } catch (e: IllegalArgumentException) {
                val isLimit = isRemoteViewsLimitException(e)
                if (isLimit) {
                    AppLogger.e("Widget update failed due to size limit. Detection confirmed.", e)
                    UpdateResult.LimitExceeded
                } else {
                    AppLogger.e("Widget update failed with IllegalArgumentException (not a size limit)", e)
                    UpdateResult.GenericFailure
                }
            } catch (e: Exception) {
                AppLogger.e("Generic widget update failure", e)
                UpdateResult.GenericFailure
            }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: IllegalArgumentException): Boolean {
            // Основной критерий: TransactionTooLargeException в cause-цепочке.
            // Это единственный достоверный признак переполнения Binder-транзакции,
            // гарантированный на всех версиях Android API 26–37.
            if (hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)) {
                AppLogger.w("RemoteViews size limit detected via TransactionTooLargeException", e)
                return true
            }

            // Fallback: текстовые паттерны для случаев, когда система не обернула
            // ошибку в TransactionTooLargeException. Это не гарантировано документацией,
            // но расширяет покрытие на нестандартных прошивках.
            val message = e.message ?: ""
            val isMessageRelated =
                LIMIT_EXCEPTION_PATTERNS.any { pattern ->
                    pattern.containsMatchIn(message)
                }

            if (isMessageRelated) {
                AppLogger.w("RemoteViews size limit detected via message pattern: $message", e)
            }

            return isMessageRelated
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
            private val LIMIT_EXCEPTION_PATTERNS =
                listOf(
                    // Оригинальный паттерн (RemoteViews + TransactionTooLarge)
                    Regex("RemoteViews.*TransactionTooLarge", RegexOption.IGNORE_CASE),
                    // "exceeded maximum size"
                    Regex("exceeded maximum size", RegexOption.IGNORE_CASE),
                    // "RemoteViews size"
                    Regex("RemoteViews size", RegexOption.IGNORE_CASE),
                    // "Binder transaction" (Binder transaction failed)
                    Regex("Binder transaction", RegexOption.IGNORE_CASE),
                )
        }
    }
