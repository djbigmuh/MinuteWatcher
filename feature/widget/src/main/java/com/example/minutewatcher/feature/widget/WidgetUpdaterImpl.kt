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
import kotlin.time.Duration.Companion.seconds

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
            withTimeoutOrNull(30.seconds) {
                updateMutex.withLock {
                    var lastOutcome: UpdateOutcome? = null
                    val success =
                        retryManager.updateWithRetry { attempt ->
                            performSingleUpdate(attempt).also { lastOutcome = it }
                        }

                    if (!success && lastOutcome is UpdateOutcome.LimitExceeded) {
                        val settings =
                            appSettingsRepository
                                .getAppSettings()
                                .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)

                        when {
                            settings == null -> {
                                AppLogger.e(
                                    "WidgetUpdater: Timeout fetching settings after LimitExceeded. " +
                                        "Proceeding to simplified update without persisting flag.",
                                )
                                val result = safeUpdateInternal(context, isFullAttempt = false, isProbe = false)
                                result is UpdateResult.Success
                            }

                            settings.widgetLimitExceeded -> false
                            else -> performSimplifiedFallback()
                        }
                    } else {
                        success
                    }
                }
            } ?: run {
                AppLogger.w("WidgetUpdater: updateAll timed out after 30s (mutex may still be held by previous call)")
                false
            }

        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome {
            val settings =
                appSettingsRepository
                    .getAppSettings()
                    .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
                    ?: appSettingsRepository.observeAppSettings().value
            val mode =
                if (settings.widgetLimitExceeded) {
                    if (limitManager.shouldProbe()) RenderMode.PROBE else RenderMode.SIMPLIFIED
                } else {
                    limitManager.resetCounter()
                    RenderMode.FULL
                }

            val result =
                withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                    AppLogger.d("WidgetUpdater: performing update in mode $mode (attempt $attempt)")
                    when (mode) {
                        RenderMode.PROBE ->
                            limitManager.performProbeUpdate(context) { ctx, _ ->
                                safeUpdateInternal(ctx, isFullAttempt = true, isProbe = true)
                            }
                        RenderMode.SIMPLIFIED -> safeUpdateInternal(context, isFullAttempt = false, isProbe = false)
                        RenderMode.FULL -> safeUpdateInternal(context, isFullAttempt = true, isProbe = false)
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
                    UpdateOutcome.LimitExceeded
                }

                is UpdateResult.GenericFailure -> {
                    if (result.isRetryable) UpdateOutcome.Retry else UpdateOutcome.Stop
                }
            }

        private suspend fun performSimplifiedFallback(): Boolean {
            AppLogger.w("Widget update failed due to size limit. Rendering with simplified layout (fallback).")
            limitManager.setWidgetLimitExceeded(value = true)

            repeat(MAX_SIMPLIFIED_ATTEMPTS) { attempt ->
                val result = safeUpdateInternal(context, isFullAttempt = false, isProbe = false)
                if (result is UpdateResult.Success) return true
                if (attempt < MAX_SIMPLIFIED_ATTEMPTS - 1) {
                    AppLogger.w("Simplified widget update attempt ${attempt + 1} failed, retrying")
                    kotlinx.coroutines.delay(Constants.Widget.RETRY_BACKOFF_MS.milliseconds)
                }
            }
            AppLogger.e("Simplified widget update failed after $MAX_SIMPLIFIED_ATTEMPTS attempts")
            return false
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun safeUpdateInternal(
            context: Context,
            isFullAttempt: Boolean,
            isProbe: Boolean,
        ): UpdateResult =
            try {
                renderer.renderAll(context, forceFull = isProbe)
                UpdateResult.Success(wasFull = isFullAttempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorClassifier.classifyUpdateError(e, isFullAttempt)
            }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean = errorClassifier.isRemoteViewsLimitException(e)

        private enum class RenderMode {
            FULL,
            SIMPLIFIED,
            PROBE,
        }

    companion object {
        private const val MAX_SIMPLIFIED_ATTEMPTS = 2
    }
    }
