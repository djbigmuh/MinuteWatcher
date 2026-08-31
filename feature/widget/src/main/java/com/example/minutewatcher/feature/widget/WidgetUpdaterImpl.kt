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
        // Step 1: Check for in-progress update.
        // We only join if the update is not yet completed.
        val activeUpdate = currentUpdate.get()
        if (activeUpdate != null && !activeUpdate.isCompleted) {
            return joinInProgressUpdate(activeUpdate)
        }

        // Step 2: Attempt to acquire lock for new update
        if (!updateMutex.tryLock()) {
            // Lock held by another thread. Coalesce if possible.
            yield() // Give owner a chance to register the deferred
            val retryUpdate = currentUpdate.get()
            if (retryUpdate != null && !retryUpdate.isCompleted) {
                return joinInProgressUpdate(retryUpdate)
            }

            // If still null or completed, the previous update just finished or is finishing.
            // We wait for the lock to start a fresh update.
            updateMutex.lock()

            // After acquiring lock, check again if another thread started an update
            // while we were waiting for the lock.
            val postLockUpdate = currentUpdate.get()
            if (postLockUpdate != null && !postLockUpdate.isCompleted) {
                updateMutex.unlock()
                return joinInProgressUpdate(postLockUpdate)
            }
        }

        // We hold the lock. Create and register deferred.
        val deferred = CompletableDeferred<WidgetUpdateResult>()
        currentUpdate.set(deferred)

        try {
            val result: WidgetUpdateResult =
                try {
                    var lastOutcome: UpdateOutcome? = null
                    val success =
                        retryManager.updateWithRetry { attempt ->
                            performSingleUpdate(attempt).also { lastOutcome = it }
                        }

                    if (success) {
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
                } catch (t: Throwable) {
                    AppLogger.e("WidgetUpdater: updateAll failed", t)
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = false,
                        reason = WidgetUpdateResult.FailureReason.TERMINAL,
                    )
                }

            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            // Defensive: ensure deferred is completed if we crash/cancel
            if (t !is CancellationException) {
                deferred.complete(
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = false,
                        reason = WidgetUpdateResult.FailureReason.TERMINAL,
                    ),
                )
            }
            throw t
        } finally {
            // Step 3: Cleanup. Unlock FIRST, then clear reference using CAS.
            // This ensures that any concurrent caller that fails tryLock()
            // will see the deferred as non-null and join it properly.
            // CAS ensures we don't overwrite a NEW update's deferred.
            updateMutex.unlock()
            currentUpdate.compareAndSet(deferred, null)
        }
    }

    private suspend fun joinInProgressUpdate(deferred: CompletableDeferred<WidgetUpdateResult>): WidgetUpdateResult {
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

        @Suppress("TooGenericExceptionCaught")
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
