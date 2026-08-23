package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * WidgetUpdaterImpl updates all widget instances with an exponential backoff retry strategy.
 * Delegates responsibilities to specialized managers for modularity.
 */
@Singleton
class WidgetUpdaterImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appSettingsRepository: AppSettingsRepository,
        private val renderer: WidgetRenderer,
        private val retryManager: WidgetUpdateRetryManager,
        private val limitManager: WidgetLimitManager,
        private val errorClassifier: WidgetErrorClassifier,
    ) : WidgetUpdater {
        private val updateMutex = Mutex()

        override suspend fun updateAll(): Boolean =
            updateMutex.withLock {
                retryManager.updateWithRetry { attempt ->
                    performSingleUpdate(attempt)
                }
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome {
            val settings = appSettingsRepository.observeAppSettings().value
            val mode =
                if (settings.widgetLimitExceeded) {
                    if (limitManager.shouldProbe()) RenderMode.PROBE else RenderMode.SIMPLIFIED
                } else {
                    limitManager.resetCounter()
                    RenderMode.FULL
                }

            val result =
                withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                    try {
                        when (mode) {
                            RenderMode.PROBE ->
                                limitManager.performProbeUpdate(context) { ctx, _ ->
                                    safeUpdateInternal(ctx, isFullAttempt = true)
                                }
                            RenderMode.SIMPLIFIED -> safeUpdateInternal(context, isFullAttempt = false)
                            RenderMode.FULL -> safeUpdateInternal(context, isFullAttempt = true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        AppLogger.e("Security error updating widgets (attempt $attempt)", e)
                        UpdateResult.GenericFailure(isRetryable = false)
                    } catch (e: Error) {
                        AppLogger.e("Fatal error updating widgets (attempt $attempt)", e)
                        UpdateResult.GenericFailure(isRetryable = false)
                    } catch (e: Exception) {
                        AppLogger.e("Unexpected error updating widgets (attempt $attempt)", e)
                        UpdateResult.GenericFailure(isRetryable = true)
                    }
                } ?: UpdateResult.GenericFailure(isRetryable = true)

            return handleUpdateResult(result)
        }

        private suspend fun handleUpdateResult(result: UpdateResult): UpdateOutcome =
            when (result) {
                is UpdateResult.Success -> {
                    if (result.wasFull) {
                        limitManager.setWidgetLimitExceeded(value = false)
                    }
                    UpdateOutcome.Success()
                }

                is UpdateResult.LimitExceeded -> {
                    handleLimitExceeded()
                }

                is UpdateResult.GenericFailure -> {
                    if (result.isRetryable) UpdateOutcome.Retry else UpdateOutcome.Stop
                }
            }

        @Suppress("ReturnCount")
        private suspend fun handleLimitExceeded(): UpdateOutcome =
            withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                var settings =
                    appSettingsRepository.getAppSettings().firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)

                val settingsTimedOut = settings == null
                if (settingsTimedOut) {
                    AppLogger.e(
                        "WidgetUpdater: Timeout fetching settings in handleLimitExceeded. Using default AppSettings.",
                    )
                    settings =
                        com.example.minutewatcher.core.model
                            .AppSettings()
                }

                val alreadyExceeded =
                    if (settings.widgetLimitExceeded) {
                        true
                    } else {
                        limitManager.setWidgetLimitExceeded(value = true)
                        false
                    }

                if (settingsTimedOut) return@withTimeoutOrNull UpdateOutcome.Stop

                if (alreadyExceeded) return@withTimeoutOrNull UpdateOutcome.Stop

                AppLogger.w("Widget update failed due to size limit. Rendering with simplified layout.")

                val result = safeUpdateInternal(context, isFullAttempt = false)
                return@withTimeoutOrNull when (result) {
                    is UpdateResult.Success -> UpdateOutcome.Success()
                    is UpdateResult.LimitExceeded -> {
                        AppLogger.e("CRITICAL: Simplified layout also exceeds Binder limit.")
                        UpdateOutcome.Stop
                    }
                    is UpdateResult.GenericFailure -> {
                        if (result.isRetryable) UpdateOutcome.Retry else UpdateOutcome.Stop
                    }
                }
            } ?: run {
                AppLogger.e("WidgetUpdater: handleLimitExceeded timed out completely.")
                UpdateOutcome.Stop
            }

        private suspend fun safeUpdateInternal(
            context: Context,
            isFullAttempt: Boolean,
        ): UpdateResult =
            try {
                renderer.renderAll(context)
                UpdateResult.Success(wasFull = isFullAttempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                errorClassifier.classifyUpdateError(e, isFullAttempt)
            }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean = errorClassifier.isRemoteViewsLimitException(e)

        private enum class RenderMode {
            FULL,
            SIMPLIFIED,
            PROBE,
        }
    }
