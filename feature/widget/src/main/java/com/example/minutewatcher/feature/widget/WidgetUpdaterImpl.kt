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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        private var updateJob: Job? = null

        private sealed class UpdateResult {
            object Success : UpdateResult()

            object LimitExceeded : UpdateResult()

            object GenericFailure : UpdateResult()
        }

        override fun updateAll() {
            updateJob?.cancel()
            updateJob = scope.launch {
                try {
                    when (safeUpdateAll(context)) {
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
                            AppLogger.d("Retrying widget update after generic failure...")
                            delay(Constants.Widget.RETRY_BACKOFF_MS.milliseconds) // Backoff before retry
                            when (safeUpdateAll(context)) {
                                is UpdateResult.Success -> setWidgetLimitExceeded(false)
                                is UpdateResult.LimitExceeded -> {
                                    setWidgetLimitExceeded(true)
                                    AppLogger.w(
                                        "Retry also hit size limit. " +
                                            "Flag set to trigger simplified layout on next update.",
                                    )
                                }
                                is UpdateResult.GenericFailure -> AppLogger.e("Widget update retry also failed")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Unexpected error updating widgets", e)
                }
            }
        }

        private suspend fun setWidgetLimitExceeded(value: Boolean) {
            try {
                appSettingsRepository.updateAppSettings {
                    it.copy(widgetLimitExceeded = value)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                AppLogger.e("Failed to set widget limit exceeded flag to $value", ex)
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
                    setWidgetLimitExceeded(true)
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
         * Эвристически определяет, вызвана ли ошибка [IllegalArgumentException] при обновлении виджета
         * превышением лимита размера Binder/RemoteViews (TransactionTooLargeException).
         *
         * Проверка основана на двух критериях:
         * 1. Наличие [android.os.TransactionTooLargeException] в цепочке причин (наиболее надежный способ).
         * 2. Наличие упоминания "RemoteViews" в сообщении об ошибке, что часто встречается в исключениях,
         *    бросаемых системой при маршалинге больших структур RemoteViews, даже если корень ошибки
         *    не обернут в TransactionTooLargeException.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: IllegalArgumentException): Boolean {
            val isTransactionTooLarge = hasCauseOfType(e, android.os.TransactionTooLargeException::class.java)

            // Проверяем сообщение исключения на упоминание RemoteViews.
            // Это эвристика, так как система может выбрасывать IAE с текстом о размере данных.
            val isMessageLimit = e.message?.contains(MARKER_REMOTE_VIEWS_SHORT, ignoreCase = true) == true

            return isTransactionTooLarge || isMessageLimit
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
            private const val MARKER_REMOTE_VIEWS_SHORT = "RemoteViews"
        }
    }
