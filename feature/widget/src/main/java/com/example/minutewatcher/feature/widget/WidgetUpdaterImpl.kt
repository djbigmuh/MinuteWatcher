package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.glance.appwidget.updateAll
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        @ApplicationScope private val scope: CoroutineScope,
        private val appSettingsRepository: AppSettingsRepository,
    ) : WidgetUpdater {
        private val widget = MinutesGlanceWidget()
        private val updateMutex = Mutex()

        private sealed class UpdateResult {
            object Success : UpdateResult()

            object LimitExceeded : UpdateResult()

            object GenericFailure : UpdateResult()
        }

        override fun updateAll() {
            scope.launch {
                updateMutex.withLock {
                    try {
                        val result = attemptUpdateWithRetry(context)
                        when (result) {
                            is UpdateResult.Success -> {
                                setWidgetLimitExceeded(false)
                            }
                            is UpdateResult.LimitExceeded -> {
                                setWidgetLimitExceeded(true)
                                AppLogger.w(
                                    "Widget update failed due to size limit. " +
                                        "Flag set to trigger simplified layout on next update.",
                                )
                            }
                            is UpdateResult.GenericFailure -> {
                                AppLogger.e("Widget update failed after $MAX_UPDATE_ATTEMPTS attempts")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Unexpected error updating widgets", e)
                    }
                }
            }
        }

        private suspend fun attemptUpdateWithRetry(context: Context): UpdateResult {
            var delayMs = Constants.Widget.RETRY_BACKOFF_MS
            var lastResult: UpdateResult = UpdateResult.GenericFailure
            for (attempt in 1..MAX_UPDATE_ATTEMPTS) {
                lastResult = safeUpdateAll(context)
                if (lastResult is UpdateResult.Success || lastResult is UpdateResult.LimitExceeded) {
                    return lastResult
                }
                if (attempt < MAX_UPDATE_ATTEMPTS) {
                    AppLogger.w("Widget update attempt $attempt failed, retrying in ${delayMs}ms")
                    delay(delayMs.milliseconds)
                    delayMs *= 2
                }
            }
            return lastResult
        }

        private suspend fun setWidgetLimitExceeded(value: Boolean) {
            try {
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
            } catch (e: IllegalArgumentException) {
                val isLimit = isRemoteViewsLimitException(e)
                if (isLimit) {
                    AppLogger.e("Widget update failed due to size limit. Detection confirmed.", e)
                    UpdateResult.LimitExceeded
                } else {
                    AppLogger.e("Widget update failed with IllegalArgumentException (not a size limit)", e)
                    UpdateResult.GenericFailure
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Generic widget update failure", e)
                UpdateResult.GenericFailure
            }

        /**
         * Эвристически определяет, вызвана ли ошибка [IllegalArgumentException]
         * при обновлении виджета превышением лимита размера Binder/RemoteViews
         * (TransactionTooLargeException).
         *
         * Данная эвристика используется для автоматического переключения виджета в упрощенный режим (SIMPLE),
         * если текущая разметка слишком велика для передачи в системный процесс.
         *
         * Критерии срабатывания:
         * 1. [android.os.TransactionTooLargeException] в цепочке причин (наиболее надежный).
         * 2. Сообщение содержит подстроки "TransactionTooLarge" или "too large" в сочетании с "RemoteViews"
         *    (fallback для случаев, когда исходная причина была утеряна при пробросе).
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: IllegalArgumentException): Boolean {
            val isTransactionTooLarge = hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)

            val message = e.message ?: ""
            // Проверяем сообщение исключения на упоминание RemoteViews и лимитов.
            val isMessageRelated =
                message.contains("RemoteViews", ignoreCase = true) &&
                    (
                        message.contains("TransactionTooLarge", ignoreCase = true) ||
                            message.contains("too large", ignoreCase = true)
                    )

            val result = isTransactionTooLarge || isMessageRelated

            if (result) {
                AppLogger.w("RemoteViews size limit detection triggered (result=$result). Exception details: $e")
            }

            return result
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
            private const val MAX_UPDATE_ATTEMPTS = 4
        }
    }
