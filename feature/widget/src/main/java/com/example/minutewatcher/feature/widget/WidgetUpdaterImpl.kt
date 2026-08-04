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
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetUpdaterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val appSettingsRepository: AppSettingsRepository,
    ) : WidgetUpdater {
        private val widget = MinutesGlanceWidget()

        override fun updateAll() {
            scope.launch {
                try {
                    val success = safeUpdateAll(context)
                    if (success) {
                        appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = false) }
                    } else {
                        AppLogger.d("Retrying widget update...")
                        val retrySuccess = safeUpdateAll(context)
                        if (retrySuccess) {
                            appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = false) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Unexpected error updating widgets", e)
                }
            }
        }

        private suspend fun safeUpdateAll(context: Context): Boolean =
            try {
                widget.updateAll(context)
                true
            } catch (e: IllegalArgumentException) {
                val isLimit = isRemoteViewsLimitException(e)
                AppLogger.e(
                    "Widget update failed. isRemoteViewsLimit=$isLimit. Message: ${e.message}",
                    e,
                )
                if (isLimit) {
                    try {
                        appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = true) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ex: Exception) {
                        AppLogger.e("Failed to set widget limit exceeded flag", ex)
                    }
                }
                false
            }

        @VisibleForTesting
        fun isRemoteViewsLimitException(e: IllegalArgumentException): Boolean {
            val msg = e.message ?: ""
            val mentionsRemoteViews = msg.contains(Constants.Widget.REMOTE_VIEWS_ERROR_MARKER, ignoreCase = true)
            val mentionsSize =
                msg.contains("too large", ignoreCase = true) ||
                    msg.contains("exceeds", ignoreCase = true) ||
                    msg.contains("TransactionTooLarge", ignoreCase = true)

            val mentionsTransactionSize =
                msg.contains("transaction", ignoreCase = true) &&
                    msg.contains("too large", ignoreCase = true)

            return (mentionsRemoteViews && mentionsSize) ||
                mentionsTransactionSize ||
                e.cause is android.os.TransactionTooLargeException
        }
    }
