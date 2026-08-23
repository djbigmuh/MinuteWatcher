package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of call tracking logic that monitors call events and records duration.
 * Uses a Mutex to prevent race conditions during concurrent call events.
 */
@Singleton
@Suppress("LongParameterList")
open class CallTracker
    @Inject
    internal constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
        private val simUsageRepository: SimUsageRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val callSessionRepository: CallSessionRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        private val permissionManager: PermissionManager,
        private val telephonyManager: TelephonyManager,
        private val retryHelper: DataStoreRetryHelper,
        @com.example.minutewatcher.core.common.di.ApplicationScope private val scope: CoroutineScope,
        private val attributionResolver: CallAttributionResolver,
        private val startHandler: CallStartHandler,
        private val endHandler: CallEndHandler,
    ) : TrackingStatusProvider {
        override val key: String = "CallTracker"
        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val callMutex = Mutex()
        private val callEndResolver =
            CallEndResolver(
                context,
                callSessionRepository,
                permissionManager,
                telephonyManager,
                dataChangeNotifier,
                ::endCall,
                { _status.value = it },
            )

        /**
         * Records call start time for a specific subscription ID.
         *
         * Requires [Manifest.permission.READ_PHONE_STATE].
         * Safe to call from any coroutine context.
         */
        suspend fun onCallStarted(subId: Int) {
            val shouldProceed =
                try {
                    if (!isCallTrackingEnabled()) {
                        AppLogger.d("CallTracker: tracking disabled, ignoring OFFHOOK for subId $subId")
                        false
                    } else {
                        true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleStartTrackingCheckError(e)
                    false
                }

            if (!shouldProceed) return

            startHandler.executeCallStart(
                subId = subId,
                callMutex = callMutex,
                statusFlow = _status,
                checkPermissions = ::checkPermissions,
                cleanupCounters = ::cleanupCounters,
                refreshStatus = ::refreshStatus,
            )
        }

        private fun handleStartTrackingCheckError(e: Exception) {
            if (e is SecurityException) {
                AppLogger.e("SecurityException checking if tracking enabled", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            } else {
                AppLogger.e("Error checking if tracking enabled", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        /**
         * Records call end time and updates minutes used.
         *
         * Requires [Manifest.permission.READ_PHONE_STATE].
         * Safe to call from any coroutine context.
         */
        suspend fun onCallEnded(subId: Int) {
            val shouldProceed =
                try {
                    !handleTrackingDisabled(subId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleEndTrackingCheckError(e)
                    false
                }

            if (!shouldProceed) return

            endHandler.performCallEnd(
                subId = subId,
                callEndResolver = callEndResolver,
                statusFlow = _status,
                checkPermissions = ::checkPermissions,
                cleanupCounters = ::cleanupCounters,
                refreshStatus = ::refreshStatus,
                endCallAction = ::endCall,
            )
        }

        private fun handleEndTrackingCheckError(e: Exception) {
            if (e is SecurityException) {
                AppLogger.e("SecurityException checking if tracking enabled", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            } else {
                AppLogger.e("Error checking if tracking enabled in onCallEnded", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        private suspend fun handleTrackingDisabled(subId: Int): Boolean {
            if (!isCallTrackingEnabled()) {
                AppLogger.d("CallTracker: tracking disabled, clearing session for subId $subId")
                callMutex.withLock {
                    if (subId == -1) {
                        callSessionRepository.clearAllCallSessions()
                    } else {
                        cleanupCounters(subId)
                    }
                }
                return true
            }
            return false
        }

        private suspend fun isCallTrackingEnabled(): Boolean {
            val settings =
                retryHelper
                    .readWithRetry(
                        label = "isCallTrackingEnabled",
                        reader = { appSettingsRepository.getAppSettings() },
                    ).getOrThrow()
            return settings.callTrackingEnabled
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
        internal open suspend fun refreshStatus(force: Boolean = false) {
            try {
                val currentStatus = _status.value
                if (!force) {
                    if (currentStatus is TrackingStatus.Error &&
                        currentStatus.type != TrackingStatus.ErrorType.PERMISSION_MISSING
                    ) {
                        return
                    }
                }

                if (!checkPermissions()) return

                val activeCalls = callSessionRepository.getActiveCallSubIds()
                _status.value = if (activeCalls.isNotEmpty()) TrackingStatus.Active else TrackingStatus.Idle

                val activeSubIdsSet = activeCalls.toSet()
                attributionResolver.retainOnlyActiveSubIds(activeSubIdsSet)
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
                callMutex = callMutex,
                statusFlow = _status,
                cleanupCounters = ::cleanupCounters,
                writeCallDuration = ::writeCallDuration,
            )
        }

        private suspend fun writeCallDuration(
            identity: String,
            durationMinutes: Int,
            isFallbackUsed: Boolean,
            notify: Boolean,
        ): Boolean {
            simUsageRepository.incrementMinutesUsed(identity, durationMinutes)
            if (isFallbackUsed) {
                appSettingsRepository.updateAppSettings {
                    it.copy(inaccurateCallAttributionsCount = it.inaccurateCallAttributionsCount + 1)
                }
            }
            if (notify) {
                dataChangeNotifier.notifyDataChanged()
            }
            AppLogger.d("Call recorded: $durationMinutes min for $identity (fallback=$isFallbackUsed)")
            return true
        }

        private suspend fun cleanupCounters(subId: Int) {
            try {
                callSessionRepository.setCallStartTime(subId, 0L)
                callSessionRepository.setCallIdentity(subId, null)
                callSessionRepository.setBootTime(subId, 0L)
                callSessionRepository.setUptime(subId, 0L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Session cleanup failed for subId $subId", e)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Session cleanup failed: ${e.message}",
                    )
                throw e
            } finally {
                attributionResolver.clearFailures(subId)
            }
        }

        fun onBoot() {
            scope.launch {
                try {
                    callMutex.withLock {
                        callSessionRepository.clearAllCallSessions()
                        attributionResolver.clearAllFailures()
                        AppLogger.d("CallTracker: cleared all sessions on boot")
                    }
                } catch (e: Exception) {
                    AppLogger.e("Error during boot cleanup", e)
                } finally {
                    refreshStatus(force = true)
                }
            }
        }

        override suspend fun recover() {
            try {
                callMutex.withLock {
                    callSessionRepository.clearAllCallSessions()
                    attributionResolver.clearAllFailures()
                    AppLogger.d("CallTracker: cleared all sessions during recovery")
                }
            } catch (e: Exception) {
                AppLogger.e("Error during recovery", e)
            } finally {
                refreshStatus(force = true)
            }
        }
    }
