package com.example.minutewatcher.core.data

import android.Manifest
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.TrackingStatusMachine
import com.example.minutewatcher.core.model.isSticky
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of call tracking logic that monitors call events and records duration.
 * Uses a Mutex to prevent race conditions during concurrent call events.
 *
 * NOTE: Suppressing TooManyFunctions as this class acts as a central coordinator for call tracking,
 * delegating most logic to handlers but maintaining public API and status management.
 */
@Singleton
@Suppress("TooManyFunctions")
open class CallTracker
    @Inject
    internal constructor(
        private val callSessionRepository: CallSessionRepository,
        private val permissionManager: PermissionManager,
        @ApplicationScope private val scope: CoroutineScope,
        private val attributionResolver: CallAttributionResolver,
        private val startHandler: CallStartHandler,
        private val endHandler: CallEndHandler,
        private val sessionCleaner: CallSessionCleaner,
        private val permissionStateNotifier: PermissionStateNotifier,
        private val callMutexHolder: CallMutexHolder,
    ) : TrackingStatusProvider,
        CallTrackingContext {
        override val key: TrackingKey = TrackingKey.CALLS

        /** Internal marker to distinguish tracker shutdown from general cancellation. */
        private class CallTrackerStoppedException : CancellationException("CallTracker stopped")

        private val statusMachine = TrackingStatusMachine()
        override val status: StateFlow<TrackingStatus> = statusMachine.status

        /**
         * Updates the tracker status using canonical prioritized rules.
         */
        override fun updateStatus(
            status: TrackingStatus,
            force: Boolean,
        ) {
            statusMachine.transition(status, force)
        }

        private fun transitionStatus(
            newStatus: TrackingStatus,
            force: Boolean = false,
        ) {
            updateStatus(newStatus, force)
        }

        private enum class State {
            IDLE,
            INITIALIZING,
            INITIALIZED,
            FAILED,
        }

        private val trackerState = AtomicReference(State.IDLE)
        private val permissionObserverJob = AtomicReference<Job?>(null)
        private val initJob = AtomicReference<Job?>(null)

        @Volatile
        private var initDeferred = CompletableDeferred<Unit>()
        private val initMutex = Mutex()

        init {
            // No coroutines launched here to avoid side effects during construction.
            // Initialization is triggered explicitly via ensureStarted() from public entry points
            // or through the DI layer for eager start.
        }

        /**
         * Ensures the tracker is started.
         * This method is idempotent and thread-safe.
         */
        open fun ensureStarted() {
            if (trackerState.compareAndSet(State.IDLE, State.INITIALIZING) ||
                trackerState.compareAndSet(State.FAILED, State.INITIALIZING)
            ) {
                synchronized(this) {
                    if (initDeferred.isCompleted) {
                        initDeferred = CompletableDeferred()
                    }
                }
                try {
                    startPermissionObserver()
                    val newInitJob =
                        scope.launch {
                            try {
                                initMutex.withLock {
                                    refreshStatus(force = true)
                                }
                                trackerState.set(State.INITIALIZED)
                                AppLogger.d("CallTracker: Initialization successful")
                                initDeferred.complete(Unit)
                            } catch (e: CancellationException) {
                                trackerState.set(State.IDLE)
                                AppLogger.d("CallTracker: Initialization cancelled")
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("CallTracker: Failed to initialize call tracking", e)
                                trackerState.set(State.FAILED)
                                initDeferred.completeExceptionally(e)
                            }
                        }
                    initJob.set(newInitJob)
                } catch (e: CancellationException) {
                    trackerState.set(State.IDLE)
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("CallTracker: Failed to start initialization", e)
                    trackerState.set(State.FAILED)
                    transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
                    initDeferred.completeExceptionally(e)
                }
            }
        }

        @VisibleForTesting
        fun isStarted(): Boolean = trackerState.get() == State.INITIALIZED || trackerState.get() == State.INITIALIZING

        private fun startPermissionObserver() {
            val changesFlow = permissionStateNotifier.changes
            val newJob =
                scope.launch {
                    changesFlow
                        .retryWhen { cause, attempt ->
                            if (attempt < MAX_RETRY_ATTEMPTS && cause !is CancellationException) {
                                val delayTime =
                                    (
                                        2.0.pow(attempt.toDouble()) *
                                            RETRY_DELAY_BASE_MS
                                    ).toLong().coerceAtMost(RETRY_DELAY_MAX_MS)
                                AppLogger.w(
                                    "CallTracker: Permission observer failed (attempt $attempt), " +
                                        "retrying in ${delayTime}ms",
                                    cause,
                                )
                                delay(delayTime.milliseconds)
                                true
                            } else {
                                false
                            }
                        }.catch { e ->
                            AppLogger.e("CallTracker: Permission observer died permanently", e)
                            transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
                        }.collect {
                            try {
                                initMutex.withLock {
                                    refreshStatus(force = true)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("CallTracker: Failed to refresh status on permission change", e)
                            }
                        }
                }
            permissionObserverJob.getAndSet(newJob)?.cancel()
        }

        /**
         * Records call start time for a specific subscription ID.
         *
         * Requires [Manifest.permission.READ_PHONE_STATE].
         * Safe to call from any coroutine context.
         *
         * @param subId Subscription ID.
         * @param eventElapsedRealtime Time when the event was captured (SystemClock.elapsedRealtime).
         * -1 if unknown.
         * @throws CancellationException if the calling coroutine is cancelled.
         * Does NOT throw if the tracker is stopped while waiting for initialization.
         */
        suspend fun onCallStarted(
            subId: Int,
            eventElapsedRealtime: Long = -1L,
        ) {
            ensureStarted()
            try {
                initDeferred.await()
            } catch (
                @Suppress("SwallowedException")
                e: CallTrackerStoppedException,
            ) {
                AppLogger.w("CallTracker: stopped while waiting for init, proceeding anyway")
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("SwallowedException")
                e: Exception,
            ) {
                AppLogger.w("CallTracker: init failed, proceeding anyway", e)
            }
            startHandler.executeCallStart(
                subId = subId,
                eventElapsedRealtime = eventElapsedRealtime,
                context = this,
            )
        }

        /**
         * Records call end time and updates minutes used.
         *
         * Requires [Manifest.permission.READ_PHONE_STATE].
         * Safe to call from any coroutine context.
         *
         * @param subId Subscription ID.
         * @param eventElapsedRealtime Time when the event was captured (SystemClock.elapsedRealtime).
         * -1 if unknown.
         * @throws CancellationException if the calling coroutine is cancelled.
         * Does NOT throw if the tracker is stopped while waiting for initialization.
         */
        suspend fun onCallEnded(
            subId: Int,
            eventElapsedRealtime: Long = -1L,
        ) {
            ensureStarted()
            try {
                initDeferred.await()
            } catch (
                @Suppress("SwallowedException")
                e: CallTrackerStoppedException,
            ) {
                AppLogger.w("CallTracker: stopped while waiting for init, proceeding anyway")
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("SwallowedException")
                e: Exception,
            ) {
                AppLogger.w("CallTracker: init failed, proceeding anyway", e)
            }
            endHandler.performCallEnd(
                subId = subId,
                eventElapsedRealtime = eventElapsedRealtime,
                context = this,
            )
        }

        /**
         * Refreshes the tracker status based on current system state.
         *
         * State machine transitions:
         * - Sticky errors ([TrackingStatus.ErrorType.DATABASE_ERROR]): Not cleared by
         *   non-forced refreshes. Requires [force] or [recover] to clear.
         * - Permission errors ([TrackingStatus.ErrorType.PERMISSION_MISSING]): Re-evaluated
         *   on every refresh. Cleared automatically if permissions are regained.
         * - Transient errors ([TrackingStatus.ErrorType.UNKNOWN],
         *   [TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION]): Cleared by any successful
         *   refresh attempt even if not forced.
         *
         * @param force If true, skips sticky error gating and forces a full re-evaluation.
         * Use force=true during manual recovery or initialization.
         *
         * **Thread Safety:** Safe to call from any coroutine context. Holds internal mutex
         * during DataStore operations.
         */
        @Suppress("ReturnCount")
        override suspend fun refreshStatus(force: Boolean) {
            AppLogger.d("CallTracker: refreshStatus called (force=$force)")
            try {
                val currentStatus = statusMachine.current
                if (!force) {
                    if (currentStatus is TrackingStatus.Error &&
                        currentStatus.type.isSticky() &&
                        currentStatus.type != TrackingStatus.ErrorType.PERMISSION_MISSING
                    ) {
                        AppLogger.d(
                            "CallTracker: refreshStatus skipped (sticky ${currentStatus.type} error, not forced)",
                        )
                        return
                    }
                }

                if (!checkPermissions()) {
                    AppLogger.d("CallTracker: refreshStatus skipped (permissions missing)")
                    return
                }

                val activeCalls = callSessionRepository.getActiveCallSubIds()
                if (activeCalls == null) {
                    AppLogger.w("CallTracker: active sessions read timed out, keeping current status")
                    return
                }

                AppLogger.d("CallTracker: refreshStatus found ${activeCalls.size} active calls")
                // We use force = true here because we have successfully re-evaluated the state
                // and want this result to override any previous error.
                transitionStatus(
                    if (activeCalls.isNotEmpty()) TrackingStatus.Active else TrackingStatus.Idle,
                    force = true,
                )

                val activeSubIdsSet = activeCalls.toSet()
                attributionResolver.retainOnlyActiveSubIds(activeSubIdsSet)
            } catch (e: CancellationException) {
                AppLogger.d("CallTracker: refreshStatus cancelled")
                throw e
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException refreshing status", e)
                transitionStatus(
                    TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message),
                    force = force,
                )
            } catch (e: Exception) {
                AppLogger.e("Error refreshing status", e)
                transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message), force = force)
            }
        }

        override fun checkPermissions(): Boolean =
            try {
                if (!permissionManager.hasPhoneStatePermission()) {
                    transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING))
                    false
                } else {
                    true
                }
            } catch (e: SecurityException) {
                AppLogger.e("Permission revoked in checkPermissions", e)
                transitionStatus(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message))
                false
            }

        private suspend fun clearSessionsAndRefresh(tag: String) {
            var operationCompleted = false
            try {
                callMutexHolder.mutex.withLock {
                    sessionCleaner.clearAllSessions()
                    AppLogger.d("CallTracker: cleared all sessions during $tag")
                }
                operationCompleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error during $tag", e)
            } finally {
                if (operationCompleted) {
                    withContext(NonCancellable) {
                        try {
                            refreshStatus(force = true)
                        } catch (e: Exception) {
                            AppLogger.e("CallTracker: Failed to refresh status in finally during $tag", e)
                        }
                    }
                }
            }
        }

        open fun onBoot() {
            ensureStarted()
            scope.launch {
                onBootAwaited()
            }
        }

        /**
         * Clears stale call sessions after boot.
         *
         * Safe to call from any coroutine context.
         * **Contract:** This method must be called early in the boot chain to ensure
         * data consistency.
         */
        open suspend fun onBootAwaited() {
            ensureStarted()
            try {
                // Clear sessions under mutex protection.
                // This doesn't wait for full initialization to avoid deadlock/hangs,
                // but uses the same mutex to ensure sequential access to tracking state.
                initMutex.withLock {
                    clearSessionsAndRefresh("boot cleanup")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("CallTracker: boot cleanup failed", e)
            }
        }

        override suspend fun recover() {
            ensureStarted()
            startPermissionObserver()
            try {
                initMutex.withLock {
                    clearSessionsAndRefresh("recovery")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("CallTracker: recovery failed", e)
            }
        }

        /**
         * Stops call tracking and cleans up resources.
         *
         * Production code does NOT call this method; tracker lifecycle is bound to the process lifecycle.
         * Used for testing purposes to ensure isolation.
         */
        @VisibleForTesting
        fun stop() {
            initJob.getAndSet(null)?.cancel()
            permissionObserverJob.getAndSet(null)?.cancel()
            trackerState.set(State.IDLE)
            synchronized(this) {
                // Cancel current deferred to unblock any awaiters (e.g. in workers or handlers)
                if (!initDeferred.isCompleted) {
                    initDeferred.completeExceptionally(CallTrackerStoppedException())
                }
                // Prepare fresh deferred for potential future re-start
                initDeferred = CompletableDeferred()
            }
            transitionStatus(TrackingStatus.Idle, force = true)
        }

        companion object {
            private const val MAX_RETRY_ATTEMPTS = 10
            private const val RETRY_DELAY_BASE_MS = 1000
            private const val RETRY_DELAY_MAX_MS = 30000L
        }
    }
