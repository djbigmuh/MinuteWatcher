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
                            appSettingsRepository.updateAppSettings {
                                it.copy(widgetLimitExceeded = false)
                            }
                        }
                        is UpdateResult.LimitExceeded -> {
                            AppLogger.w("Widget update failed due to size limit. Skipping retry.")
                        }
                        is UpdateResult.GenericFailure -> {
                            AppLogger.d("Retrying widget update after generic failure...")
                            val retryResult = safeUpdateAll(context)
                            if (retryResult is UpdateResult.Success) {
                                appSettingsRepository.updateAppSettings {
                                    it.copy(widgetLimitExceeded = false)
                                }
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

        private suspend fun safeUpdateAll(context: Context): UpdateResult =
            try {
                widget.updateAll(context)
                UpdateResult.Success
            } catch (e: IllegalArgumentException) {
                val isLimit = isRemoteViewsLimitException(e)
                AppLogger.e(
                    "Widget update failed. isRemoteViewsLimit=$isLimit. Message: ${e.message}",
                    e,
                )
                if (isLimit) {
                    try {
                        appSettingsRepository.updateAppSettings {
                            it.copy(widgetLimitExceeded = true)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ex: Exception) {
                        AppLogger.e("Failed to set widget limit exceeded flag", ex)
                    }
                    UpdateResult.LimitExceeded
                } else {
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
            val msg = e.message ?: ""
            val mentionsRemoteViews = msg.contains(Constants.Widget.REMOTE_VIEWS_ERROR_MARKER, ignoreCase = true)
            val mentionsSize =
                msg.contains("too large", ignoreCase = true) ||
                    msg.contains("exceeds", ignoreCase = true) ||
                    msg.contains("TransactionTooLarge", ignoreCase = true)

            val mentionsTransactionSize =
                msg.contains("transaction", ignoreCase = true) &&
                    msg.contains("too large", ignoreCase = true)

            return (
                (mentionsRemoteViews && mentionsSize) ||
                    mentionsTransactionSize ||
                    (e.cause is android.os.TransactionTooLargeException)
            )
        }
    }
