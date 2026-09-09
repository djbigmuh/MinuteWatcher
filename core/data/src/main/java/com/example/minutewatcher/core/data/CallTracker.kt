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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
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

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    /**
     * Updates the tracker status using a prioritized transition rule to prevent race conditions.
     * Priority: PERMISSION_MISSING > DATABASE_ERROR > UNKNOWN (Error) > Active = Idle.
     *
     * Transitions:
     * 1. Higher or equal priority always overrides.
     * 2. Healthy states (Active, Idle) can clear transient UNKNOWN errors.
     * 3. DATABASE_ERROR and PERMISSION_MISSING are sticky and require 'force' or explicit recovery to clear.
     */
    override fun updateStatus(
        status: TrackingStatus,
        force: Boolean,
    ) {
        _status.update { current ->
            val currentPrio = getStatusPriority(current)
            val newPrio = getStatusPriority(status)

            if (force || newPrio >= currentPrio) status else current
        }
    }

    private fun transitionStatus(
        newStatus: TrackingStatus,
        force: Boolean = false,
    ) {
        updateStatus(newStatus, force)
        }

    private fun getStatusPriority(status: TrackingStatus): Int =
        when (status) {
            is TrackingStatus.Error ->
                when (status.type) {
                    TrackingStatus.ErrorType.PERMISSION_MISSING -> TrackingStatus.PRIORITY_PERMISSION_MISSING
                    TrackingStatus.ErrorType.DATABASE_ERROR -> TrackingStatus.PRIORITY_DATABASE_ERROR
                    else -> TrackingStatus.PRIORITY_TRANSIENT_ERROR
                }

            is TrackingStatus.Active, is TrackingStatus.Idle -> TrackingStatus.PRIORITY_HEALTHY
            else -> 0
        }

    private enum class State {
        IDLE,
        INITIALIZING,
        INITIALIZED,
        FAILED,
    }

    private val trackerState = AtomicReference(State.IDLE)
    private var permissionObserverJob: Job? = null
    private var initJob: Job? = null

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
    fun ensureStarted() {
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
                initJob =
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
        permissionObserverJob?.cancel()
        permissionObserverJob =
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
    }

    /**
     * Records call start time for a specific subscription ID.
     *
     * Requires [Manifest.permission.READ_PHONE_STATE].
     * Safe to call from any coroutine context.
     */
    suspend fun onCallStarted(subId: Int) {
        ensureStarted()
        try {
            initDeferred.await()
        } catch (_: Exception) {
            // Initialization failed, but we still try to proceed or the status will reflect the error
        }
            startHandler.executeCallStart(
                subId = subId,
                context = this,
            )
    }

    /**
     * Records call end time and updates minutes used.
     *
     * Requires [Manifest.permission.READ_PHONE_STATE].
     * Safe to call from any coroutine context.
     */
    suspend fun onCallEnded(subId: Int) {
        ensureStarted()
        try {
            initDeferred.await()
        } catch (_: Exception) {
            // Proceed anyway
        }
            endHandler.performCallEnd(
                subId = subId,
                context = this,
            )
        }

    /**
     * Refreshes the tracker status based on current system state.
     *
     * State machine transitions:
     * - [TrackingStatus.ErrorType.PERMISSION_MISSING]: "Sticky" error, only cleared by a
     *   successful refresh (Active/Idle).
     * - [TrackingStatus.ErrorType.DATABASE_ERROR]: "Sticky" error, usually requires manual
     *   recovery or successful write to clear.
     * - [TrackingStatus.ErrorType.UNKNOWN]: Transient error, can be cleared by any successful
     *   refresh attempt even if not forced.
     *
     * @param force If true, skips priority checks and forces a full re-evaluation.
     * Use force=true during manual recovery or initialization.
     *
     * **Thread Safety:** Safe to call from any coroutine context. Holds internal mutex
     * during DataStore operations.
     */
    override suspend fun refreshStatus(force: Boolean) {
        AppLogger.d("CallTracker: refreshStatus called (force=$force)")
        try {
            val currentStatus = _status.value
            if (!force) {
                if ((currentStatus is TrackingStatus.Error) &&
                    (currentStatus.type != TrackingStatus.ErrorType.PERMISSION_MISSING)
                ) {
                    AppLogger.d("CallTracker: refreshStatus skipped (not forced and has non-permission error)")
                    return
                }
            }

            if (!checkPermissions()) {
                AppLogger.d("CallTracker: refreshStatus skipped (permissions missing)")
                return
            }

            val activeCalls = callSessionRepository.getActiveCallSubIds()
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

    private suspend fun endCall(
        subId: Int,
        notify: Boolean = true,
    ) {
        endHandler.endCall(
            subId = subId,
            notify = notify,
            context = this,
        )
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

    fun onBoot() {
        ensureStarted()
        scope.launch {
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
     * Guaranteed to complete or cancel any pending initialization waiters.
     */
    fun stop() {
        initJob?.cancel()
        initJob = null
        permissionObserverJob?.cancel()
        permissionObserverJob = null
        trackerState.set(State.IDLE)
        synchronized(this) {
            // Cancel current deferred to unblock any awaiters (e.g. in workers or handlers)
            if (!initDeferred.isCompleted) {
                initDeferred.completeExceptionally(CancellationException("CallTracker stopped"))
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
