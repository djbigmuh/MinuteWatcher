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
                    widget.updateAll(context)
                    try {
                        appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = false) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Failed to reset widget limit exceeded flag", e)
                    }
                } catch (e: IllegalArgumentException) {
                    val msg = e.message ?: ""
                    val isLimitExceeded = isRemoteViewsLimitException(e)

                    AppLogger.e(
                        "Widget update failed with IllegalArgumentException. isRemoteViewsLimit=$isLimitExceeded. Message: $msg",
                        e,
                    )

                    if (isLimitExceeded) {
                        var flagSaved = false
                        try {
                            appSettingsRepository.updateAppSettings { it.copy(widgetLimitExceeded = true) }
                            flagSaved = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("Failed to set widget limit exceeded flag", e)
                        }

                        if (flagSaved) {
                            try {
                                AppLogger.d("Retrying widget update after limit exceeded")
                                widget.updateAll(context)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Retry widget update failed", e)
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

        @VisibleForTesting
        fun isRemoteViewsLimitException(e: IllegalArgumentException): Boolean {
            val msg = e.message ?: ""
            val mentionsRemoteViews = msg.contains(Constants.Widget.REMOTE_VIEWS_ERROR_MARKER, ignoreCase = true)
            val mentionsSize =
                msg.contains("too large", ignoreCase = true) ||
                    msg.contains("exceeds", ignoreCase = true) ||
                    msg.contains("TransactionTooLarge", ignoreCase = true)
            return (mentionsRemoteViews && mentionsSize) ||
                e.cause is android.os.TransactionTooLargeException
        }
    }
