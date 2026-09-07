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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * WidgetUpdaterImpl updates all widget instances with coalescing logic.
 * Delegates responsibilities to specialized managers for modularity.
 */
@Singleton
@Suppress("TooManyFunctions")
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
    private val coalescingMutex = Mutex()
    private var activeUpdate: CompletableDeferred<WidgetUpdateResult>? = null
    private var limitExceededHandled = AtomicBoolean(false)

    /**
     * Triggers an update for all application widgets.
     *
     * This operation is typically bounded by a timeout (e.g., WIDGET_UPDATE_TIMEOUT_MS).
     * Supports coalescing: if an update is already in progress, the caller will join it.
     *
     * @return [WidgetUpdateResult] indicating the outcome of the update.
     * @throws CancellationException if the coroutine scope is cancelled.
     */
    override suspend fun updateAll(): WidgetUpdateResult {
        val (deferred, isCoalesced) =
            coalescingMutex.withLock {
                val active = activeUpdate?.takeIf { !it.isCompleted }
                if (active != null) {
                    active to true
                } else {
                    val new = CompletableDeferred<WidgetUpdateResult>()
                    activeUpdate = new
                    new to false
                }
            }

        val result =
            if (isCoalesced) {
                joinInProgressUpdate(deferred)
            } else {
                startNewUpdate(deferred)
            }

        return result
    }

    private suspend fun startNewUpdate(deferred: CompletableDeferred<WidgetUpdateResult>): WidgetUpdateResult =
        try {
            val result =
                withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                    executeUpdateProcess()
                } ?: WidgetUpdateResult.Failure(
                    wasCoalesced = false,
                    isRetryable = true,
                    reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                )
            deferred.complete(result)
            result
        } catch (e: CancellationException) {
            deferred.complete(
                WidgetUpdateResult.Failure(
                    wasCoalesced = false,
                    isRetryable = true,
                    reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                ),
            )
            throw e
        } catch (e: Exception) {
            AppLogger.e("WidgetUpdater: update failed", e)
            val failure =
                WidgetUpdateResult.Failure(
                    wasCoalesced = false,
                    isRetryable = false,
                    reason = WidgetUpdateResult.FailureReason.TERMINAL,
                )
            deferred.complete(failure)
            failure
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            AppLogger.e("WidgetUpdater: unexpected throwable during update", e)
            deferred.complete(
                WidgetUpdateResult.Failure(
                    wasCoalesced = false,
                    isRetryable = false,
                    reason = WidgetUpdateResult.FailureReason.TERMINAL,
                ),
            )
            throw e
        } finally {
            coalescingMutex.withLock {
                if (activeUpdate === deferred) {
                    activeUpdate = null
                }
            }
        }

    private suspend fun executeUpdateProcess(): WidgetUpdateResult =
        try {
            var lastOutcome: UpdateOutcome? = null
            val success =
                retryManager.updateWithRetry { attempt ->
                    try {
                        performSingleUpdate(attempt).also { lastOutcome = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("WidgetUpdater: attempt $attempt threw exception", e)
                        UpdateOutcome.Retry.also { lastOutcome = it }
                    }
                }

            if (success) {
                WidgetUpdateResult.Success(wasCoalesced = false)
            } else {
                handleUnsuccessfulUpdate(lastOutcome)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("WidgetUpdater: update process failed", e)
            WidgetUpdateResult.Failure(
                wasCoalesced = false,
                isRetryable = false,
                reason = WidgetUpdateResult.FailureReason.TERMINAL,
            )
        }

    private suspend fun handleUnsuccessfulUpdate(lastOutcome: UpdateOutcome?): WidgetUpdateResult {
        if (lastOutcome is UpdateOutcome.LimitExceeded) {
            return handleLimitExceeded()
        }

        val isRetryable = lastOutcome == UpdateOutcome.Retry || lastOutcome == null
        return WidgetUpdateResult.Failure(
            wasCoalesced = false,
            isRetryable = isRetryable,
            reason =
                if (isRetryable) {
                    WidgetUpdateResult.FailureReason.TRANSIENT
                } else {
                    WidgetUpdateResult.FailureReason.TERMINAL
                },
        )
    }

    private suspend fun joinInProgressUpdate(
        deferred: CompletableDeferred<WidgetUpdateResult>,
    ): WidgetUpdateResult {
        AppLogger.d("WidgetUpdater: update already in progress, waiting")
            val result = deferred.await()
        return when (result) {
                is WidgetUpdateResult.Success -> result.copy(wasCoalesced = true)
                is WidgetUpdateResult.Failure -> result.copy(wasCoalesced = true)
            }
    }

        private suspend fun handleLimitExceeded(): WidgetUpdateResult {
            if (!limitExceededHandled.compareAndSet(false, true)) {
                return WidgetUpdateResult.Failure(
                    wasCoalesced = false,
                    isRetryable = false,
                    reason = WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED,
                )
            }
            limitManager.setWidgetLimitExceeded(value = true)

            var lastOutcome: UpdateOutcome = UpdateOutcome.Retry
            val success =
                retryManager.updateWithRetry { _ ->
                    val result = safeUpdateInternal(context, isFullAttempt = false, isProbe = false)
                    when (result) {
                        is UpdateResult.Success -> UpdateOutcome.Success().also { lastOutcome = it }
                        is UpdateResult.LimitExceeded -> UpdateOutcome.LimitExceeded.also { lastOutcome = it }
                        is UpdateResult.GenericFailure -> {
                            if (result.isRetryable) {
                                UpdateOutcome.Retry.also { lastOutcome = it }
                            } else {
                                UpdateOutcome.Stop.also { lastOutcome = it }
                            }
                        }
                    }
                }

            return createLimitExceededResult(success, lastOutcome)
        }

    private suspend fun createLimitExceededResult(
        success: Boolean,
        lastOutcome: UpdateOutcome,
    ): WidgetUpdateResult =
        when {
                success -> {
                    limitManager.setWidgetLimitExceeded(value = false)
                    limitExceededHandled.set(false)
                    WidgetUpdateResult.Success(wasCoalesced = false)
                }

                lastOutcome is UpdateOutcome.LimitExceeded ->
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = false,
                        reason = WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED,
                    )

                else -> {
                    val isRetryable = lastOutcome == UpdateOutcome.Retry
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = isRetryable,
                        reason =
                            if (isRetryable) {
                                WidgetUpdateResult.FailureReason.TRANSIENT
                            } else {
                                WidgetUpdateResult.FailureReason.TERMINAL
                            },
                    )
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
                    limitExceededHandled.set(false)
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

        private suspend fun safeUpdateInternal(
            context: Context,
            isFullAttempt: Boolean,
            isProbe: Boolean,
        ): UpdateResult =
            try {
                renderer.renderAll(context, forceFull = isFullAttempt || isProbe)
                UpdateResult.Success(wasFull = isFullAttempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorClassifier.classifyUpdateError(e, isFullAttempt)
            }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun isRemoteViewsLimitException(e: Throwable): Boolean = errorClassifier.isRemoteViewsLimitException(e)

    override suspend fun recover() {
        limitExceededHandled.set(false)
    }

        private enum class RenderMode {
            FULL,
            SIMPLIFIED,
            PROBE,
        }

        companion object {
            // No-op
        }
    }
