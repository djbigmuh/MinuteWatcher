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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicReference
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
    private val updateMutex = Mutex()
    private val currentUpdate = AtomicReference<CompletableDeferred<WidgetUpdateResult>?>(null)

    override suspend fun updateAll(): WidgetUpdateResult {
        repeat(5) {
            currentUpdate.get()?.takeIf { !it.isCompleted }?.let { return joinInProgressUpdate(it) }

            if (updateMutex.tryLock()) {
                val deferred = CompletableDeferred<WidgetUpdateResult>()
                currentUpdate.set(deferred)
                try {
                    val result = executeUpdateProcess()
                    deferred.complete(result)
                    return result
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val failure =
                        WidgetUpdateResult.Failure(
                            wasCoalesced = false,
                            isRetryable = false,
                            reason = WidgetUpdateResult.FailureReason.TERMINAL,
                        )
                    deferred.complete(failure)
                    return failure
                } finally {
                    currentUpdate.compareAndSet(deferred, null)
                    updateMutex.unlock()
                }
            }
            yield()
            currentUpdate.get()?.takeIf { !it.isCompleted }?.let { return joinInProgressUpdate(it) }
            updateMutex.lock()
            updateMutex.unlock()
        }
        return WidgetUpdateResult.Failure(
            wasCoalesced = false,
            isRetryable = true,
            reason = WidgetUpdateResult.FailureReason.TRANSIENT,
        )
    }

    private suspend fun executeUpdateProcess(): WidgetUpdateResult =
        try {
            var lastOutcome: UpdateOutcome? = null
            val success =
                retryManager.updateWithRetry { attempt ->
                    performSingleUpdate(attempt).also { lastOutcome = it }
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

        val isRetryable = lastOutcome == UpdateOutcome.Retry
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
        return try {
            withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                deferred.await()
            }?.let { result ->
                when (result) {
                    is WidgetUpdateResult.Success -> result.copy(wasCoalesced = true)
                    is WidgetUpdateResult.Failure -> result.copy(wasCoalesced = true)
                }
            } ?: WidgetUpdateResult.Failure(
                wasCoalesced = true,
                isRetryable = true,
                reason = WidgetUpdateResult.FailureReason.TRANSIENT,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("WidgetUpdater: joinInProgressUpdate failed", e)
            WidgetUpdateResult.Failure(
                wasCoalesced = true,
                isRetryable = true,
                reason = WidgetUpdateResult.FailureReason.TRANSIENT,
            )
        }
    }

        private suspend fun handleLimitExceeded(): WidgetUpdateResult {
            limitManager.setWidgetLimitExceeded(value = true)

            var lastOutcome: UpdateOutcome? = null
            val success =
                retryManager.updateWithRetry { _ ->
                    when (val result = safeUpdateInternal(context, isFullAttempt = false, isProbe = false)) {
                        is UpdateResult.Success -> UpdateOutcome.Success()
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

            return when {
                success -> WidgetUpdateResult.Success(wasCoalesced = false)
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

        private suspend fun safeUpdateInternal(
            context: Context,
            isFullAttempt: Boolean,
            isProbe: Boolean,
        ): UpdateResult =
            try {
                val success =
                    withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        renderer.renderAll(context, forceFull = isFullAttempt || isProbe)
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

        companion object {
            // No-op
        }
    }
