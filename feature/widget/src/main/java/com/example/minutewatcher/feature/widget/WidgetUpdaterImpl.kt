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

        private sealed class UpdateResult {
            object Success : UpdateResult()

            object LimitExceeded : UpdateResult()

            object GenericFailure : UpdateResult()
        }

        override fun updateAll() {
            scope.launch {
                try {
                    when (safeUpdateAll(context)) {
                        is UpdateResult.Success -> {
                            setWidgetLimitExceeded(false)
                        }
                        is UpdateResult.LimitExceeded -> {
                            AppLogger.w("Widget update failed due to size limit. Re-rendering with simplified layout.")
                            try {
                                widget.updateAll(context)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Widget re-render after size limit failure also failed", e)
                            }
                        }
                        is UpdateResult.GenericFailure -> {
                            AppLogger.d("Retrying widget update after generic failure...")
                            delay(Constants.Widget.RETRY_BACKOFF_MS.milliseconds) // Backoff before retry
                            when (safeUpdateAll(context)) {
                                is UpdateResult.Success -> setWidgetLimitExceeded(false)
                                is UpdateResult.LimitExceeded -> {
                                    AppLogger.w("Retry also hit size limit. Re-rendering with simplified layout.")
                                    try {
                                        widget.updateAll(context)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        AppLogger.e("Widget re-render after size limit failure also failed", e)
                                    }
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
         * Heuristically determines if an [IllegalArgumentException] during widget update
         * is caused by exceeding the Binder/RemoteViews size limit (TransactionTooLargeException).
         *
         * This check includes reliable cause analysis and stack trace inspection.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: IllegalArgumentException): Boolean {
            val isTransactionTooLarge = e.cause is android.os.TransactionTooLargeException
            val stackTrace = e.stackTraceToString()
            val isStackTraceLimit =
                stackTrace.contains(MARKER_REMOTE_VIEWS, ignoreCase = true) &&
                    stackTrace.contains(MARKER_TRANSACTION_TOO_LARGE, ignoreCase = true)

            return isTransactionTooLarge || isStackTraceLimit
        }

        companion object {
            private const val MARKER_REMOTE_VIEWS = "RemoteViews"
            private const val MARKER_TRANSACTION_TOO_LARGE = "TransactionTooLarge"
        }
    }
