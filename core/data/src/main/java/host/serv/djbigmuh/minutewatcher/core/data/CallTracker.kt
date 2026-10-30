package host.serv.djbigmuh.minutewatcher.core.data

import android.Manifest
import androidx.annotation.VisibleForTesting
import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.observeWithRetry
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatusMachine
import host.serv.djbigmuh.minutewatcher.core.model.isSticky
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of call tracking logic that monitors call events and records duration.
 * Uses a Mutex (via [CallMutexHolder]) to prevent race conditions during concurrent call events.
 */
@Singleton
class CallTracker
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

        private enum class State {
            IDLE,
            INITIALIZING,
            INITIALIZED,
            FAILED,
        }

        private val trackerState = AtomicReference(State.IDLE)

        @VisibleForTesting
        internal val permissionObserverJob = AtomicReference<Job?>(null)
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
         *
         * State transition from [State.IDLE] or [State.FAILED] to [State.INITIALIZING] and replacement
         * of completed [initDeferred] occur atomically within a single synchronized block. This guarantees
         * that concurrent calls to [awaitInitialization] or [onCallStarted] will not observe a completed
         * [initDeferred] from a previous failed cycle while a new initialization cycle is in progress.
         *
         * This method is idempotent and thread-safe.
         */
        fun ensureStarted() {
            val shouldStart =
                synchronized(this) {
                    val current = trackerState.get()
                    if (current == State.IDLE || current == State.FAILED) {
                        trackerState.set(State.INITIALIZING)
                        if (initDeferred.isCompleted) {
                            initDeferred = CompletableDeferred()
                        }
                        true
                    } else {
                        false
                    }
                }

            if (shouldStart) {
                try {
                    startPermissionObserver()
                    val previousInit = initJob.getAndSet(null)
                    previousInit?.cancel()
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
                                AppLogger.d("CallTracker: Initialization canceled")
                                throw e
                            } catch (e: Exception) {
                                AppLogger.e("CallTracker: Failed to initialize call tracking", e)
                                trackerState.set(State.FAILED)
                                initDeferred.completeExceptionally(e)
                            }
                        }
                    if (!initJob.compareAndSet(null, newInitJob)) {
                        newInitJob.cancel()
                    }
                } catch (e: Exception) {
                    AppLogger.e("CallTracker: Failed to start initialization", e)
                    trackerState.set(State.FAILED)
                    updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message))
                    initDeferred.completeExceptionally(e)
                }
            }
        }

        @VisibleForTesting
        internal fun isStarted(): Boolean {
            val current = trackerState.get()
            return current == State.INITIALIZED || current == State.INITIALIZING
        }

        private val observerLock = Any()

        private fun startPermissionObserver() {
            synchronized(observerLock) {
                val existing = permissionObserverJob.get()
                if (existing == null || !existing.isActive) {
                    val previous = permissionObserverJob.getAndSet(null)
                    previous?.cancel()
                    val changesFlow = permissionStateNotifier.changes
                    val newJob =
                        scope.launch {
                            changesFlow
                                .observeWithRetry(
                                    label = "CallTracker Permission observer",
                                    logThreshold = LOG_THRESHOLD,
                                    onPersistentFailure = { cause ->
                                        updateStatus(
                                            TrackingStatus.Error(
                                                TrackingStatus.ErrorType.UNKNOWN,
                                                cause.message,
                                            ),
                                        )
                                    },
                                    onRecovered = {
                                        updateStatus(TrackingStatus.Idle)
                                    },
                                    onSourceCompleted = {
                                        AppLogger.e("CallTracker: Permission observer completed normally (unexpected)")
                                        updateStatus(
                                            TrackingStatus.Error(
                                                TrackingStatus.ErrorType.UNKNOWN,
                                                "Permission observer completed normally",
                                            ),
                                        )
                                    },
                                ).collect {
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
                    if (!permissionObserverJob.compareAndSet(null, newJob)) {
                        newJob.cancel()
                    }
                }
            }
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
         * @throws IOException If a database or storage error occurs during call start recording.
         * @throws CancellationException if the calling coroutine is canceled.
         * Does NOT throw if the tracker is stopped while waiting for initialization.
         */
        suspend fun onCallStarted(
            subId: Int,
            eventElapsedRealtime: Long = -1L,
        ) {
            ensureStarted()
            awaitInitialization()
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
         * @throws IOException If a database or storage error occurs during call end recording.
         * @throws CancellationException if the calling coroutine is canceled.
         * Does NOT throw if the tracker is stopped while waiting for initialization.
         */
        suspend fun onCallEnded(
            subId: Int,
            eventElapsedRealtime: Long = -1L,
        ) {
            ensureStarted()
            awaitInitialization()
            endHandler.performCallEnd(
                subId = subId,
                eventElapsedRealtime = eventElapsedRealtime,
                context = this,
            )
        }

        private suspend fun awaitInitialization() {
            try {
                initDeferred.await()
            } catch (_: CallTrackerStoppedException) {
                AppLogger.w("CallTracker: stopped while waiting for init, proceeding anyway")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("CallTracker: init failed, proceeding anyway", e)
            }
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
                updateStatus(
                    if (activeCalls.isNotEmpty()) TrackingStatus.Active else TrackingStatus.Idle,
                    force = true,
                )

                val activeSubIdsSet = activeCalls.toSet()
                attributionResolver.retainOnlyActiveSubIds(activeSubIdsSet)
            } catch (e: CancellationException) {
                AppLogger.d("CallTracker: refreshStatus canceled")
                throw e
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException refreshing status", e)
                updateStatus(
                    TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message),
                    force = force,
                )
            } catch (e: Exception) {
                AppLogger.e("Error refreshing status", e)
                updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message), force = force)
            }
        }

        override fun checkPermissions(): Boolean =
            try {
                if (!permissionManager.hasPhoneStatePermission()) {
                    updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING))
                    false
                } else {
                    true
                }
            } catch (e: SecurityException) {
                AppLogger.e("Permission revoked in checkPermissions", e)
                updateStatus(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message))
                false
            }

        private suspend fun clearSessionsAndRefresh(tag: String) {
            var operationCompleted = false
            try {
                callMutexHolder.mutex.withLock {
                    val count = sessionCleaner.clearStaleSessions(this)
                    AppLogger.d("CallTracker: cleared $count stale sessions during $tag")
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

        /**
         * Clears stale call sessions after boot.
         *
         * Waits for completion of any critical section holding [initMutex] (such as status refresh)
         * rather than waiting for the entire initialization cycle, using [initMutex] to ensure
         * thread-safe sequential access to tracking state.
         *
         * Safe to call from any coroutine context.
         * **Contract:** This method must be called early in the boot chain to ensure
         * data consistency.
         */
        suspend fun onBootAwaited() {
            ensureStarted()
            try {
                // Waits for initMutex holding critical sections before performing cleanup.
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
        internal fun stop() {
            initJob.getAndSet(null)?.cancel()
            permissionObserverJob.getAndSet(null)?.cancel()
            synchronized(this) {
                trackerState.set(State.IDLE)
                // Cancel current deferred to unblock any awaiters (e.g. in workers or handlers)
                if (!initDeferred.isCompleted) {
                    initDeferred.completeExceptionally(CallTrackerStoppedException())
                }
                // Prepare fresh deferred for potential future re-start
                initDeferred = CompletableDeferred()
            }
            updateStatus(TrackingStatus.Idle, force = true)
        }

        companion object {
            private const val LOG_THRESHOLD = 3
        }
    }
