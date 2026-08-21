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
            retryManager.updateWithRetry { attempt ->
                performSingleUpdate(attempt)
            }

        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome =
            updateMutex.withLock {
                val outcome =
                    withTimeoutOrNull(WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        val result =
                            try {
                                val settings = appSettingsRepository.observeAppSettings().value
                                if (settings.widgetLimitExceeded) {
                                    if (limitManager.shouldProbe()) {
                                        limitManager.performProbeUpdate(context) { ctx, _ ->
                                            safeUpdateInternal(ctx, isProbe = true)
                                        }
                                    } else {
                                        safeUpdateInternal(context, isProbe = false)
                                    }
                                } else {
                                    limitManager.resetCounter()
                                    safeUpdateInternal(context, isProbe = false)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("Unexpected error updating widgets (attempt $attempt)", e)
                                UpdateResult.GenericFailure(isRetryable = true)
                            }

                        handleUpdateResult(result)
                    }
                outcome ?: handleUpdateResult(UpdateResult.GenericFailure(isRetryable = true))
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

        private suspend fun handleLimitExceeded(): UpdateOutcome {
            val settings =
                appSettingsRepository.getAppSettings().firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
                    ?: return UpdateOutcome.Retry

            if (settings.widgetLimitExceeded) return UpdateOutcome.Stop

            limitManager.setWidgetLimitExceeded(value = true)
            AppLogger.w("Widget update failed due to size limit. Rendering with simplified layout.")

            val result = safeUpdateInternal(context, isProbe = false)
            return when (result) {
                is UpdateResult.Success -> UpdateOutcome.Success()
                is UpdateResult.LimitExceeded -> {
                    AppLogger.e("CRITICAL: Simplified layout also exceeds Binder limit.")
                    UpdateOutcome.Stop
                }
                is UpdateResult.GenericFailure -> {
                    if (result.isRetryable) UpdateOutcome.Retry else UpdateOutcome.Stop
                }
            }
        }

        private suspend fun safeUpdateInternal(
            context: Context,
            isProbe: Boolean,
        ): UpdateResult {
            val limitExceeded = appSettingsRepository.observeAppSettings().value.widgetLimitExceeded
            val isFullAttempt = isProbe || !limitExceeded

            return try {
                renderer.renderAll(context)
                UpdateResult.Success(wasFull = isFullAttempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorClassifier.classifyUpdateError(e, isFullAttempt, isProbe)
            }
        }

        companion object {
            private const val WIDGET_UPDATE_TIMEOUT_MS = 10_000L
        }

        @VisibleForTesting
        constructor(
            context: Context,
            appSettingsRepository: AppSettingsRepository,
            renderer: WidgetRenderer,
        ) : this(
            context = context,
            appSettingsRepository = appSettingsRepository,
            renderer = renderer,
            retryManager = WidgetUpdateRetryManager(),
            limitManager = WidgetLimitManager(appSettingsRepository),
            errorClassifier = WidgetErrorClassifier(),
        )

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean =
            errorClassifier.isRemoteViewsLimitException(e)
    }
