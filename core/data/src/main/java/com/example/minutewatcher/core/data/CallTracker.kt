package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.TrackingStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of call tracking logic that monitors call events and records duration.
 * Uses a Mutex to prevent race conditions during concurrent call events.
 */
@Singleton
@Suppress("LongParameterList")
open class CallTracker
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        private val callSessionRepository: CallSessionRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        private val permissionManager: PermissionManager,
        telephonyManager: TelephonyManager,
        @ApplicationScope private val scope: CoroutineScope,
        private val attributionResolver: CallAttributionResolver,
        private val startHandler: CallStartHandler,
        private val endHandler: CallEndHandler,
        private val sessionCleaner: CallSessionCleaner,
        private val settingsHandler: CallTrackingSettingsHandler,
        private val permissionStateNotifier: PermissionStateNotifier,
    ) : TrackingStatusProvider {
    override val key: String = "calls"

    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val isInitialized = AtomicBoolean(false)
    private var permissionObserverJob: Job? = null

    init {
        if (isInitialized.compareAndSet(false, true)) {
            startPermissionObserver()
            scope.launch {
                try {
                    refreshStatus(force = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("CallTracker: Failed to initialize call tracking", e)
                }
            }
        }
    }

    private fun startPermissionObserver() {
        permissionObserverJob?.cancel()
        permissionObserverJob = scope.launch {
            permissionStateNotifier.changes
                .retryWhen { cause, attempt ->
                    if (attempt < 10 && cause !is CancellationException) {
                        val delayTime = (2.0.pow(attempt.toDouble()) * 1000).toLong().coerceAtMost(30000)
                        AppLogger.w(
                            "CallTracker: Permission observer failed (attempt $attempt), retrying in ${delayTime}ms",
                            cause,
                        )
                        delay(delayTime.milliseconds)
                        true
                    } else {
                        false
                    }
                }
                .catch { e ->
                    AppLogger.e("CallTracker: Permission observer died permanently", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                }
                .collect {
                    try {
                        refreshStatus(force = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("CallTracker: Failed to refresh status on permission change", e)
                    }
                }
        }
    }

    private val callMutex = Mutex()
    private val callEndResolver =
        CallEndResolver(
            context,
            callSessionRepository,
            permissionManager,
            telephonyManager,
        ) { _status.value = it }

    /**
     * Records call start time for a specific subscription ID.
     *
     * Requires [Manifest.permission.READ_PHONE_STATE].
     * Safe to call from any coroutine context.
     */
    suspend fun onCallStarted(subId: Int) {
        startHandler.executeCallStart(
            subId = subId,
            callMutex = callMutex,
            statusFlow = _status,
            checkPermissions = ::checkPermissions,
            cleanupCounters = { sessionCleaner.cleanupCounters(it, _status) },
            refreshStatus = ::refreshStatus,
        ) { settingsHandler.isCallTrackingEnabled() }
    }

    /**
     * Records call end time and updates minutes used.
     *
     * Requires [Manifest.permission.READ_PHONE_STATE].
     * Safe to call from any coroutine context.
     */
    suspend fun onCallEnded(subId: Int) {
        endHandler.performCallEnd(
            subId = subId,
            callEndResolver = callEndResolver,
            statusFlow = _status,
            checkPermissions = ::checkPermissions,
            cleanupCounters = { sessionCleaner.cleanupCounters(it, _status) },
            refreshStatus = ::refreshStatus,
            endCallAction = ::endCall,
            isCallTrackingEnabled = { settingsHandler.isCallTrackingEnabled() },
            callMutex = callMutex,
            clearAllCallSessions = { sessionCleaner.clearAllSessions() },
            notifyDataChanged = { dataChangeNotifier.notifyDataChanged() },
        )
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    internal open suspend fun refreshStatus(force: Boolean = false) {
        try {
            val currentStatus = _status.value
            if (!force) {
                if ((currentStatus is TrackingStatus.Error) &&
                    (currentStatus.type != TrackingStatus.ErrorType.PERMISSION_MISSING)
                ) {
                    return
                }
            }

            if (!checkPermissions()) return

            val activeCalls = callSessionRepository.getActiveCallSubIds()
            _status.value = if (activeCalls.isNotEmpty()) TrackingStatus.Active else TrackingStatus.Idle

            val activeSubIdsSet = activeCalls.toSet()
            attributionResolver.retainOnlyActiveSubIds(activeSubIdsSet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AppLogger.e("SecurityException refreshing status", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
        } catch (e: Exception) {
            AppLogger.e("Error refreshing status", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
        }
    }

    private fun checkPermissions(): Boolean =
        try {
            if (!permissionManager.hasPhoneStatePermission()) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
                false
            } else {
                true
            }
        } catch (e: SecurityException) {
            AppLogger.e("Permission revoked in checkPermissions", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            false
        }

    private suspend fun endCall(
        subId: Int,
        notify: Boolean = true,
        ) {
        endHandler.endCall(
            subId = subId,
            notify = notify,
            statusFlow = _status,
            cleanupCounters = { sessionCleaner.cleanupCounters(it, _status) },
            writeCallDuration = { id, dur, fallback, notif ->
                settingsHandler.writeCallDuration(id, dur, fallback, notif)
            },
        ) {
            settingsHandler.isCallTrackingEnabled()
        }
        }

    private suspend fun clearSessionsAndRefresh(tag: String) {
        var operationCompleted = false
        try {
            callMutex.withLock {
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
        scope.launch {
            try {
                clearSessionsAndRefresh("boot cleanup")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("CallTracker: boot cleanup failed", e)
            }
        }
    }

    override suspend fun recover() {
        startPermissionObserver()
        try {
            clearSessionsAndRefresh("recovery")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("CallTracker: recovery failed", e)
        }
    }
    }
