package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * WidgetUpdaterImpl updates all widget instances with coalescing logic.
 * Delegates responsibilities to specialized managers for modularity.
 *
 * This class implements a state machine for handling RemoteViews size limits:
 * 1. FULL: Default mode. Tries to render the complete widget layout.
 * 2. LimitExceeded: If FULL render fails with TransactionTooLargeException,
 *    it switches to SIMPLIFIED mode and sets the persistent widgetLimitExceeded flag.
 * 3. SIMPLIFIED: Renders a reduced layout to fit within Binder limits.
 * 4. PROBE: Every 10 updates (see PROBE_INTERVAL_UPDATES), it attempts
 *    a FULL render again to see if the situation has improved (e.g., fewer SIMs).
 *    If PROBE succeeds, it switches back to FULL and clears the flag.
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
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : WidgetUpdater {
        private val coalescingMutex = Mutex()
        private val activeUpdate = AtomicReference<CompletableDeferred<WidgetUpdateResult>?>(null)
        private var pendingPass: CompletableDeferred<WidgetUpdateResult>? = null
        private var rerunRequested: Boolean = false

        /**
         * Tracks whether a LimitExceeded error has been handled within the CURRENT update call.
         * This prevents redundant flag updates and infinite retry loops if SIMPLIFIED mode also fails.
         * Reset at the beginning of each update process, on [recover], or when a FULL update succeeds.
         */
        private val limitExceededHandled = AtomicBoolean(false)

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
                    val active = activeUpdate.get()
                    val tailScheduled = pendingPass != null
                    if (active == null && !tailScheduled) {
                        val new = CompletableDeferred<WidgetUpdateResult>()
                        activeUpdate.set(new)
                        new to false
                    } else {
                        rerunRequested = true
                        if (pendingPass == null) {
                            pendingPass = CompletableDeferred()
                        }
                        pendingPass!! to true
                    }
                }

            val result =
                if (isCoalesced) {
                    deferred.await()
                } else {
                    startNewUpdate(deferred)
                }

            return result
        }

        private suspend fun startNewUpdate(deferred: CompletableDeferred<WidgetUpdateResult>): WidgetUpdateResult {
            try {
                val res =
                    withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        executeUpdateProcess()
                    } ?: WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = true,
                        reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                    )
                deferred.complete(res)
                return res
            } catch (e: CancellationException) {
                val failure =
                    WidgetUpdateResult.Failure(
                        wasCoalesced = false,
                        isRetryable = true,
                        reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                    )
                deferred.complete(failure)
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
                return failure
            } finally {
                withContext(NonCancellable) {
                    val nextTail =
                        coalescingMutex.withLock {
                            activeUpdate.set(null)
                            if (rerunRequested) {
                                rerunRequested = false
                                val d = pendingPass
                                pendingPass = null
                                d
                            } else {
                                null
                            }
                        }
                    if (nextTail != null) {
                        scope.launch {
                            runTailPass(nextTail)
                        }
                    }
                }
            }
        }

        private suspend fun runTailPass(tailDeferred: CompletableDeferred<WidgetUpdateResult>) {
            val result =
                try {
                    val res =
                        withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                            executeUpdateProcess()
                        } ?: WidgetUpdateResult.Failure(
                            wasCoalesced = true,
                            isRetryable = true,
                            reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                        )
                    when (res) {
                        is WidgetUpdateResult.Success -> res.copy(wasCoalesced = true)
                        is WidgetUpdateResult.Failure -> res.copy(wasCoalesced = true)
                    }
                } catch (_: CancellationException) {
                    AppLogger.d("WidgetUpdater: tail pass cancelled")
                    WidgetUpdateResult.Failure(
                        wasCoalesced = true,
                        isRetryable = true,
                        reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                    )
                } catch (e: Exception) {
                    AppLogger.e("WidgetUpdater: tail pass failed", e)
                    WidgetUpdateResult.Failure(
                        wasCoalesced = true,
                        isRetryable = true,
                        reason = WidgetUpdateResult.FailureReason.TRANSIENT,
                    )
                }
            tailDeferred.complete(result)

            // Check if another rerun was requested during the tail pass
            val nextTail =
                coalescingMutex.withLock {
                    if (rerunRequested) {
                        rerunRequested = false
                        val d = pendingPass
                        pendingPass = null
                        d
                    } else {
                        null
                    }
                }
            if (nextTail != null) {
                scope.launch {
                    runTailPass(nextTail)
                }
            }
        }

        private suspend fun executeUpdateProcess(): WidgetUpdateResult =
            try {
                limitExceededHandled.set(false)
                var lastOutcome: UpdateOutcome? = null
                val success =
                    retryManager.updateWithRetry { attempt ->
                        val outcome =
                            try {
                                performSingleUpdate(attempt)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("WidgetUpdater: attempt $attempt threw exception", e)
                                UpdateOutcome.Retry
                            }

                        when (outcome) {
                            is UpdateOutcome.LimitExceeded -> {
                                if (outcome.wasFull && limitExceededHandled.compareAndSet(false, true)) {
                                    limitManager.setWidgetLimitExceeded(value = true)
                                    // Retry to switch to SIMPLIFIED mode in the next iteration
                                    UpdateOutcome.Retry
                                } else {
                                    lastOutcome = outcome
                                    UpdateOutcome.Stop
                                }
                            }

                            else -> {
                                lastOutcome = outcome
                                outcome
                            }
                        }
                    }

                if (success) {
                    WidgetUpdateResult.Success(wasCoalesced = false)
                } else {
                    createFailureResult(lastOutcome)
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

        private fun createFailureResult(lastOutcome: UpdateOutcome?): WidgetUpdateResult {
            val isRetryable = lastOutcome == UpdateOutcome.Retry || lastOutcome == null
            val reason =
                when (lastOutcome) {
                    is UpdateOutcome.LimitExceeded -> WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED
                    else ->
                        if (isRetryable) {
                            WidgetUpdateResult.FailureReason.TRANSIENT
                        } else {
                            WidgetUpdateResult.FailureReason.TERMINAL
                        }
                }
            return WidgetUpdateResult.Failure(
                wasCoalesced = false,
                isRetryable = isRetryable,
                reason = reason,
            )
        }

        private suspend fun performSingleUpdate(attempt: Int): UpdateOutcome {
            val isFirstAttempt = attempt == 1
            val mode = resolveRenderMode(isFirstAttempt)

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

        private suspend fun resolveRenderMode(isFirstAttempt: Boolean): RenderMode {
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
                if (isFirstAttempt) {
                    limitManager.incrementUpdateCounter()
                }
                if (isFirstAttempt && limitManager.shouldProbe()) RenderMode.PROBE else RenderMode.SIMPLIFIED
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
                        limitExceededHandled.set(false)
                    }
                    UpdateOutcome.Success
                }

                is UpdateResult.LimitExceeded -> {
                    UpdateOutcome.LimitExceeded(wasFull = result.wasFull)
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
    }
