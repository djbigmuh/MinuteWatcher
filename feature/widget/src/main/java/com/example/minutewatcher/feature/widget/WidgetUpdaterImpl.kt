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
        private val activeUpdate = AtomicReference<CompletableDeferred<WidgetUpdateResult>?>(null)

        override suspend fun updateAll(): WidgetUpdateResult =
            executeUpdate {
                try {
                    var lastOutcome: UpdateOutcome? = null
                    val s =
                        retryManager.updateWithRetry { attempt ->
                            performSingleUpdate(attempt).also { lastOutcome = it }
                        }

                    if (s) {
                        WidgetUpdateResult.Success(wasCoalesced = false)
                    } else if (lastOutcome is UpdateOutcome.LimitExceeded) {
                        handleLimitExceeded()
                    } else {
                        val isRetryable = lastOutcome == UpdateOutcome.Retry
                        WidgetUpdateResult.Failure(
                            wasCoalesced = false,
                            isRetryable = isRetryable,
                            reason = if (isRetryable) WidgetUpdateResult.FailureReason.TRANSIENT else WidgetUpdateResult.FailureReason.TERMINAL,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("WidgetUpdater: updateAll failed", e)
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = false,
                        reason = WidgetUpdateResult.FailureReason.TERMINAL,
                    )
                }
            }

        @Suppress("ReturnCount")
        private suspend fun executeUpdate(block: suspend () -> WidgetUpdateResult): WidgetUpdateResult {
            val deferred = CompletableDeferred<WidgetUpdateResult>()
            while (!activeUpdate.compareAndSet(null, deferred)) {
                val existing = activeUpdate.get() ?: continue
                return joinExistingUpdate(existing)
            }

            val result: WidgetUpdateResult =
                try {
                    block()
                } catch (e: CancellationException) {
                    deferred.completeExceptionally(e)
                    activeUpdate.compareAndSet(deferred, null)
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("WidgetUpdater: update failed with unexpected exception", e)
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = false,
                        reason = WidgetUpdateResult.FailureReason.TERMINAL,
                    )
                }

            try {
                if (!deferred.isCompleted) {
                    deferred.complete(result)
                }
            } finally {
                activeUpdate.compareAndSet(deferred, null)
            }

            return result
        }

        private suspend fun joinExistingUpdate(existing: CompletableDeferred<WidgetUpdateResult>): WidgetUpdateResult {
            AppLogger.d("WidgetUpdater: update already in progress, waiting")
            return try {
                val result =
                    withTimeout(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        existing.await()
                    }
                when (result) {
                    is WidgetUpdateResult.Success -> result.copy(wasCoalesced = true)
                    is WidgetUpdateResult.Failure -> result.copy(wasCoalesced = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("WidgetUpdater: joined update failed or timed out", e)
                WidgetUpdateResult.Failure(
                    wasCoalesced = true,
                    isRetryable = true,
                    reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                )
            }
        }

        private suspend fun handleLimitExceeded(): WidgetUpdateResult {
            limitManager.setWidgetLimitExceeded(value = true)
            // Немедленная попытка в упрощённом режиме
            return when (val result = safeUpdateInternal(context, isFullAttempt = false, isProbe = false)) {
                is UpdateResult.Success -> {
                    WidgetUpdateResult.Success(wasCoalesced = false)
                }

                is UpdateResult.LimitExceeded -> {
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = false,
                        reason = WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED,
                    )
                }

                is UpdateResult.GenericFailure -> {
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = result.isRetryable,
                        reason =
                            if (result.isRetryable) {
                                WidgetUpdateResult.FailureReason.TRANSIENT
                            } else {
                                WidgetUpdateResult.FailureReason.TERMINAL
                            },
                    )
                }
            }
        }

        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome {
            val mode = resolveRenderMode()

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

    private suspend fun resolveRenderMode(): RenderMode {
        val isExceeded =
            limitManager.isLimitExceeded() ?: run {
                val settings =
                    appSettingsRepository
                        .getAppSettings()
                        .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
                if (settings == null) {
                    AppLogger.w("WidgetUpdater: settings timeout in resolveRenderMode, defaulting to FULL")
                    return RenderMode.FULL
                }
                val value = settings.widgetLimitExceeded
                limitManager.updateCache(value)
                value
                }

        return if (isExceeded) {
            if (limitManager.shouldProbe()) RenderMode.PROBE else RenderMode.SIMPLIFIED
            } else {
            limitManager.resetCounter()
            RenderMode.FULL
            }
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
