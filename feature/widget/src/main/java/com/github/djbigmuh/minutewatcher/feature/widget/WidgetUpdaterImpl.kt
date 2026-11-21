package com.github.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.github.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.firstWithTimeout
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.core.model.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * Invariant: `activeUpdate` is non-null iff a pass is running or a tail pass is registered
 * and waiting to start; parallel execution of passes is excluded.
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

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal val activeUpdate = AtomicReference<CompletableDeferred<WidgetUpdateResult>?>(null)

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal val pendingPass = AtomicReference<CompletableDeferred<WidgetUpdateResult>?>(null)

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal val rerunRequested = AtomicBoolean(false)

        private val limitExceededHandled = AtomicBoolean(false)

        override suspend fun updateAll(): WidgetUpdateResult {
            val (deferred, isCoalesced) =
                coalescingMutex.withLock {
                    val active = activeUpdate.get()
                    val tailScheduled = pendingPass.get() != null
                    if (active == null && !tailScheduled) {
                        val new = CompletableDeferred<WidgetUpdateResult>()
                        activeUpdate.set(new)
                        new to false
                    } else {
                        rerunRequested.set(true)
                        val tailDef =
                            pendingPass.get() ?: CompletableDeferred<WidgetUpdateResult>().also {
                                pendingPass.set(it)
                            }
                        tailDef to true
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
            @Suppress("TooGenericExceptionCaught")
            try {
                val res =
                    withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                        executeUpdateProcess()
                    } ?: transientFailure(wasCoalesced = false)
                deferred.complete(res)
                return res
            } catch (e: CancellationException) {
                deferred.complete(transientFailure(wasCoalesced = false))
                throw e
            } catch (e: Exception) {
                AppLogger.e("WidgetUpdater: update failed", e)
                val failure = terminalFailure()
                deferred.complete(failure)
                return failure
            } finally {
                withContext(NonCancellable) {
                    scheduleNextTailPass(deferred)
                }
            }
        }

        private suspend fun runTailPass(tailDeferred: CompletableDeferred<WidgetUpdateResult>) {
            var completed = false
            @Suppress("TooGenericExceptionCaught")
            try {
                val finalRes = executeTailUpdate()
                tailDeferred.complete(finalRes)
                completed = true
            } catch (e: CancellationException) {
                AppLogger.d("WidgetUpdater: tail pass canceled")
                tailDeferred.complete(transientFailure(wasCoalesced = true))
                completed = true
                throw e
            } catch (e: Exception) {
                AppLogger.e("WidgetUpdater: tail pass failed", e)
                tailDeferred.complete(transientFailure(wasCoalesced = true))
                completed = true
            } finally {
                handleTailFinally(tailDeferred, completed)
            }
        }

        private suspend fun executeTailUpdate(): WidgetUpdateResult {
            val res =
                withTimeoutOrNull(Constants.Time.WIDGET_UPDATE_TIMEOUT_MS.milliseconds) {
                    executeUpdateProcess()
                } ?: transientFailure(wasCoalesced = true)
            return when (res) {
                is WidgetUpdateResult.Success -> res.copy(wasCoalesced = true)
                is WidgetUpdateResult.Failure -> res.copy(wasCoalesced = true)
            }
        }

        private suspend fun handleTailFinally(
            tailDeferred: CompletableDeferred<WidgetUpdateResult>,
            completed: Boolean,
        ) {
            withContext(NonCancellable) {
                if (!completed && !tailDeferred.isCompleted) {
                    tailDeferred.complete(transientFailure(wasCoalesced = true))
                }
                scheduleNextTailPass(tailDeferred)
            }
        }

        private suspend fun scheduleNextTailPass(owner: CompletableDeferred<WidgetUpdateResult>) {
            val startTime = System.currentTimeMillis()
            val nextTail =
                withTimeoutOrNull(Constants.Flow.MUTEX_ACQUIRE_TIMEOUT_MS.milliseconds) {
                    coalescingMutex.withLock {
                        if (rerunRequested.compareAndSet(true, false)) {
                            val d = pendingPass.getAndSet(null)
                            if (d != null) {
                                activeUpdate.set(d)
                            } else {
                                activeUpdate.compareAndSet(owner, null)
                            }
                            d
                        } else {
                            activeUpdate.compareAndSet(owner, null)
                            null
                        }
                    }
                }
            if (nextTail == null) {
                val duration = System.currentTimeMillis() - startTime
                AppLogger.e("WidgetUpdater: scheduleNextTailPass timed out after ${duration}ms while acquiring mutex")
                handleTailScheduleTimeout(owner)
            } else {
                scope.launch(start = CoroutineStart.ATOMIC) {
                    runTailPass(nextTail)
                }
            }
        }

        @VisibleForTesting
        internal fun handleTailScheduleTimeout(owner: CompletableDeferred<WidgetUpdateResult>) {
            val pending = pendingPass.getAndSet(null)
            if (pending != null && !pending.isCompleted) {
                pending.complete(transientFailure(wasCoalesced = true))
            }
            activeUpdate.compareAndSet(owner, null)
        }

        private fun transientFailure(wasCoalesced: Boolean): WidgetUpdateResult.Failure =
            WidgetUpdateResult.Failure(
                wasCoalesced = wasCoalesced,
                isRetryable = true,
                reason = WidgetUpdateResult.FailureReason.TRANSIENT,
            )

        private fun terminalFailure(): WidgetUpdateResult.Failure =
            WidgetUpdateResult.Failure(
                wasCoalesced = false,
                isRetryable = false,
                reason = WidgetUpdateResult.FailureReason.TERMINAL,
            )

        private suspend fun executeUpdateProcess(): WidgetUpdateResult =
            @Suppress("TooGenericExceptionCaught")
            try {
                limitExceededHandled.set(false)
                var lastOutcome: UpdateOutcome? = null
                val success =
                    retryManager.updateWithRetry { attempt ->
                        @Suppress("TooGenericExceptionCaught")
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
                terminalFailure()
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
            val isExceeded = resolveIsExceeded()
            val isFullAttempt = !isExceeded

            AppLogger.d(
                "WidgetUpdater: performing update in mode " +
                    "${if (isFullAttempt) "FULL" else "SIMPLIFIED"} (attempt $attempt)",
            )
            return handleUpdateResult(
                safeUpdateInternal(context, isFullAttempt = isFullAttempt),
            )
        }

        private suspend fun resolveIsExceeded(): Boolean {
            val isExceeded =
                limitManager.isLimitExceeded() ?: run {
                    val settings =
                        appSettingsRepository
                            .getAppSettings()
                            .firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS)
                    if (settings == null) {
                        AppLogger.w("WidgetUpdater: settings timeout in resolveIsExceeded, defaulting to false")
                        return false
                    }
                    val value = settings.widgetLimitExceeded
                    limitManager.updateCache(value)
                    value
                }
            return isExceeded
        }

        private fun handleUpdateResult(result: UpdateResult): UpdateOutcome =
            when (result) {
                is UpdateResult.Success -> {
                    if (result.wasFull) {
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
        ): UpdateResult =
            @Suppress("TooGenericExceptionCaught")
            try {
                renderer.renderAll(context)
                UpdateResult.Success(wasFull = isFullAttempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorClassifier.classifyUpdateError(e, isFullAttempt)
            }

        override suspend fun recover() {
            limitExceededHandled.set(false)
        }
    }
