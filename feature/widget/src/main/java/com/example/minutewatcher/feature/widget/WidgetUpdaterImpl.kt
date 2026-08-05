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
                            AppLogger.w("Widget update failed due to size limit. Skipping retry.")
                        }
                        is UpdateResult.GenericFailure -> {
                            AppLogger.d("Retrying widget update after generic failure...")
                            delay(Constants.Widget.RETRY_BACKOFF_MS.milliseconds) // Backoff before retry
                            when (safeUpdateAll(context)) {
                                is UpdateResult.Success -> setWidgetLimitExceeded(false)
                                is UpdateResult.LimitExceeded -> AppLogger.w("Retry also hit size limit")
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

        @VisibleForTesting
        fun isRemoteViewsLimitException(e: IllegalArgumentException): Boolean {
            // 1. Reliable check: TransactionTooLargeException as cause.
            if (e.cause is android.os.TransactionTooLargeException) return true

            // 2. Stack trace analysis for RemoteViews/IPC limits.
            val stackTrace = e.stackTraceToString()
            if (stackTrace.contains("RemoteViews", ignoreCase = true) &&
                stackTrace.contains("TransactionTooLarge", ignoreCase = true)
            ) {
                return true
            }

            // 3. Fragile heuristic fallback, may break on future Android versions.
            val msg = e.message ?: ""
            val mentionsRemoteViews = msg.contains(Constants.Widget.REMOTE_VIEWS_ERROR_MARKER, ignoreCase = true)
            val mentionsSize =
                msg.contains("too large", ignoreCase = true) ||
                    msg.contains("exceeds", ignoreCase = true) ||
                    msg.contains("TransactionTooLarge", ignoreCase = true)

            val mentionsTransactionSize =
                msg.contains("transaction", ignoreCase = true) &&
                    msg.contains("too large", ignoreCase = true)

            return (mentionsRemoteViews && mentionsSize) || mentionsTransactionSize
        }
    }
