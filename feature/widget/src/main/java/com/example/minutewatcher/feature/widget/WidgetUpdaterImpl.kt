package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.glance.appwidget.GlanceAppWidget
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
open class WidgetUpdaterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
    ) : WidgetUpdater {
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal var widget: GlanceAppWidget = MinutesGlanceWidget()
        private val updateMutex = Mutex()

        private sealed class UpdateResult {
            object Success : UpdateResult()

            object LimitExceeded : UpdateResult()

            object GenericFailure : UpdateResult()
        }

        override suspend fun updateAll(): Boolean =
            updateMutex.withLock {
                try {
                    val result = attemptUpdateWithRetry(context)
                    when (result) {
                        is UpdateResult.Success -> {
                            val current = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
                            if (current) {
                                setWidgetLimitExceeded(false)
                            }
                            true
                        }

                        is UpdateResult.LimitExceeded -> {
                            val current = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
                            if (!current) {
                                setWidgetLimitExceeded(true)
                                AppLogger.w(
                                    "Widget update failed due to size limit. " +
                                        "Flag set to trigger simplified layout on next update.",
                                )
                                // G-484: Immediate re-render to apply SIMPLE layout right away.
                                try {
                                    performWidgetUpdate(context)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e("Immediate widget re-render after limit exceeded failed", e)
                                }
                            }
                            true // Return true as we handled the limit and simplified the widget
                        }

                        is UpdateResult.GenericFailure -> {
                            AppLogger.e("Widget update failed after ${Constants.Widget.MAX_UPDATE_ATTEMPTS} attempts")
                            false
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Unexpected error updating widgets", e)
                    false
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
                performWidgetUpdate(context)
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
                    UpdateResult.GenericFailure
                }
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: RuntimeException,
            ) {
                if (isRemoteViewsLimitException(e)) {
                    AppLogger.e("Widget update failed due to size limit (wrapped).", e)
                    UpdateResult.LimitExceeded
                } else {
                    AppLogger.e("Widget update failed with RuntimeException", e)
                    UpdateResult.GenericFailure
                }
            } catch (e: Exception) {
                AppLogger.e("Generic widget update failure", e)
                UpdateResult.GenericFailure
            }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal open suspend fun performWidgetUpdate(context: Context) {
            widget.updateAll(context)
        }

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
