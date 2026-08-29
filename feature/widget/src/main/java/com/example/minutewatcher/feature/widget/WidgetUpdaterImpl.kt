package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * WidgetUpdaterImpl updates all widget instances with coalescing logic.
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
    private val activeUpdate = AtomicReference<CompletableDeferred<Boolean>?>(null)

    override suspend fun updateAll(): WidgetUpdateResult {
        var lastOutcome: UpdateOutcome? = null
        val result =
            executeUpdate {
                val s =
                    retryManager.updateWithRetry { attempt ->
                        performSingleUpdate(attempt).also { lastOutcome = it }
                    }
                if (s) {
                    UpdateResult.Success(wasFull = true)
                } else {
                    when (lastOutcome) {
                        is UpdateOutcome.LimitExceeded -> UpdateResult.LimitExceeded
                        else -> UpdateResult.GenericFailure(isRetryable = false)
                    }
                }
            }

        if ((!result.isSuccess) && (lastOutcome is UpdateOutcome.LimitExceeded)) {
            return handleLimitExceeded()
        }
        return result
    }

    @Suppress("ReturnCount")
    private suspend fun executeUpdate(block: suspend () -> UpdateResult): WidgetUpdateResult {
        val deferred = CompletableDeferred<Boolean>()
        while (!activeUpdate.compareAndSet(null, deferred)) {
            val existing = activeUpdate.get()
            if (existing != null) {
                return joinExistingUpdate(existing)
            }
        }

        var success = false
        var updateResult: UpdateResult? = null
        try {
            updateResult = block()
            success = updateResult is UpdateResult.Success
            deferred.complete(success)
        } catch (e: CancellationException) {
            deferred.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
        } finally {
            activeUpdate.compareAndSet(deferred, null)
        }

        return if (success) {
            WidgetUpdateResult.Success(wasCoalesced = false)
        } else {
            WidgetUpdateResult.Failure(
                wasCoalesced = false,
                isRetryable = (updateResult as? UpdateResult.GenericFailure)?.isRetryable ?: false,
            )
        }
    }

    private suspend fun joinExistingUpdate(existing: CompletableDeferred<Boolean>): WidgetUpdateResult {
        AppLogger.d("WidgetUpdater: update already in progress, waiting")
        return try {
            val success =
                withTimeout(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                    existing.await()
                }
            if (success) {
                WidgetUpdateResult.Success(wasCoalesced = true)
            } else {
                WidgetUpdateResult.Failure(wasCoalesced = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("WidgetUpdater: joined update failed or timed out", e)
            WidgetUpdateResult.Failure(wasCoalesced = true)
        }
    }

    private suspend fun handleLimitExceeded(): WidgetUpdateResult {
        val settings =
            appSettingsRepository
                .getAppSettings()
                .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)

        return when {
            settings == null -> {
                AppLogger.e(
                    "WidgetUpdater: Timeout fetching settings after LimitExceeded. " +
                        "Proceeding to simplified update with background flag update.",
                )
                limitManager.setWidgetLimitExceeded(value = true)
                val result = executeUpdate {
                    safeUpdateInternal(context, isFullAttempt = false, isProbe = false)
                }
                if (result is WidgetUpdateResult.Failure) {
                    limitManager.setWidgetLimitExceeded(value = false)
                }
                result
            }

            settings.widgetLimitExceeded -> WidgetUpdateResult.Failure(isRetryable = false)
            else -> performSimplifiedUpdate(isFallback = true)
        }
    }

        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome {
            val isExceeded =
                limitManager.isLimitExceeded() ?: run {
                    val settings =
                        appSettingsRepository
                            .getAppSettings()
                            .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
                        // If fetching fails, use the latest observed state.
                            ?: appSettingsRepository.observeAppSettings().value
                    settings.widgetLimitExceeded
                }

            val mode =
                if (isExceeded) {
                    if (limitManager.shouldProbe()) RenderMode.PROBE else RenderMode.SIMPLIFIED
                } else {
                    limitManager.resetCounter()
                    RenderMode.FULL
                }

            AppLogger.d("WidgetUpdater: performing update in mode $mode (attempt $attempt)")
            return when (mode) {
                RenderMode.PROBE ->
                    handleUpdateResult(
                        limitManager.performProbeUpdate(context) { ctx, _ ->
                            safeUpdateInternal(ctx, isFullAttempt = true, isProbe = true)
                        },
                    )

                RenderMode.SIMPLIFIED ->
                    handleUpdateResult(
                        safeUpdateInternal(
                            context,
                            isFullAttempt = false,
                            isProbe = false,
                        ),
                    )

                RenderMode.FULL ->
                    handleUpdateResult(
                        safeUpdateInternal(
                            context,
                            isFullAttempt = true,
                            isProbe = false,
                        ),
                    )
            }
        }

    private suspend fun performSimplifiedUpdate(isFallback: Boolean = false): WidgetUpdateResult {
        if (isFallback) {
            AppLogger.w("Widget update failed due to size limit. Rendering with simplified layout (fallback).")
            limitManager.setWidgetLimitExceeded(value = true)
        }

        val result = executeUpdate {
            val s =
                retryManager.updateWithRetry {
                    handleUpdateResult(safeUpdateInternal(context, isFullAttempt = false, isProbe = false))
                }
            if (s) UpdateResult.Success(wasFull = false) else UpdateResult.GenericFailure(isRetryable = false)
        }

        if (result is WidgetUpdateResult.Failure) {
            limitManager.setWidgetLimitExceeded(value = false)
        }
        return result
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
                    if (result.isRetryable) {
                        UpdateOutcome.Retry
                    } else {
                        AppLogger.e("Widget update: terminal failure encountered, stopping retries.")
                        UpdateOutcome.Stop
                    }
                }
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun safeUpdateInternal(
            context: Context,
            isFullAttempt: Boolean,
            isProbe: Boolean,
        ): UpdateResult =
            try {
                val success =
                    withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        renderer.renderAll(context, forceFull = isProbe)
                        true
                    } ?: false

                if (success) {
                    UpdateResult.Success(wasFull = isFullAttempt)
                } else {
                    AppLogger.w("WidgetUpdater: renderAll timed out after ${Constants.Time.WIDGET_UPDATE_TIMEOUT_MS}ms")
                    UpdateResult.GenericFailure(isRetryable = true)
                }
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
        // No-op
    }
    }
