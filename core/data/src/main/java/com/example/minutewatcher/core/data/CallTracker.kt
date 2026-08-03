package com.example.minutewatcher.core.data

import android.annotation.SuppressLint
import android.os.Build
import android.telephony.TelephonyManager
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
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

@Singleton
class CallTracker
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val callSessionRepository: CallSessionRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        private val permissionManager: PermissionManager,
        private val telephonyManager: TelephonyManager,
        @com.example.minutewatcher.core.common.di.ApplicationScope private val scope: CoroutineScope,
        private val timeProvider: TimeProvider,
    ) : TrackingStatusProvider {
        override val key: String = "calls"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val callMutex = Mutex()

        override fun recover() {
            scope.launch {
                refreshStatus(force = true)
            }
        }

        private suspend fun isCallTrackingEnabled(): Boolean =
            appSettingsRepository.getAppSettings().firstWithTimeout()?.callTrackingEnabled ?: true

        /**
         * Called on device boot to clean up any stale state.
         */
        suspend fun onBoot() {
            callMutex.withLock {
                callSessionRepository.clearAllCallSessions()
                _status.value = TrackingStatus.Idle
                AppLogger.d("Cleared all stale call sessions on boot")
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives OFFHOOK.
         */
        suspend fun onCallStarted(subId: Int) {
            callMutex.withLock {
                try {
                    if (!isCallTrackingEnabled() || !checkPermissions()) return@withLock

                    val effectiveSubId = if (subId == -1) {
                        permissionManager.getDefaultVoiceSubscriptionId()
                    } else {
                        subId
                    }

                    if (effectiveSubId == -1) {
                        AppLogger.d("Call started with unknown subId and no default voice subId, ignoring")
                        return@withLock
                    }

                    handleCallStartInternal(effectiveSubId)
                    _status.value = TrackingStatus.Active
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallStarted", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                } finally {
                    try { refreshStatus() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            }
        }

        private suspend fun handleCallStartInternal(subId: Int) {
            val startTime = callSessionRepository.getCallStartTime(subId).firstWithTimeout() ?: 0L
            val savedBootTime = callSessionRepository.getBootTime(subId).firstWithTimeout() ?: 0L
            val currentBootTime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            val isStale = startTime == 0L || savedBootTime == 0L || savedBootTime > currentBootTime

            if (isStale) {
                if (startTime != 0L || savedBootTime != 0L) {
                    AppLogger.d("Clearing stale call session for subId $subId")
                    clearSession(subId)
                }

                val identity = resolveIdentity(subId)
                callSessionRepository.setCallStartTime(subId, timeProvider.now())
                callSessionRepository.setBootTime(subId, currentBootTime)
                callSessionRepository.setUptime(subId, currentUptime)
                callSessionRepository.setCallIdentity(subId, identity)
                AppLogger.d("Call started for subId $subId")
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives IDLE.
         */
        suspend fun onCallEnded(subId: Int) {
            callMutex.withLock {
                try {
                    executeCallEnded(subId)
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallEnded", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                } finally {
                    try { refreshStatus() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            }
        }

        private suspend fun executeCallEnded(subId: Int) {
            if (!isCallTrackingEnabled() || !checkPermissions()) return

            if (subId == -1) {
                handleUnknownSubIdEnd()
            } else {
                endCall(subId)
            }
        }

        private fun checkPermissions(): Boolean {
            if (!permissionManager.isTelephonySupported()) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "SubscriptionManager unavailable")
                return false
            }
            if (!permissionManager.hasPhoneStatePermission()) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, "Phone State missing")
                return false
            }
            return true
        }

        private fun handleSecurityException(e: SecurityException) {
            AppLogger.e("SecurityException in CallTracker", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
        }

        @SuppressLint("MissingPermission")
        private suspend fun handleUnknownSubIdEnd() {
            if (!permissionManager.hasPhoneStatePermission()) return
            val activeSubIds = callSessionRepository.getActiveCallSubIds()
            if (activeSubIds.isEmpty()) {
                AppLogger.d("Call ended with unknown subId and no active sessions found")
                return
            }

            if (isDeviceIdle(activeSubIds)) {
                handleGlobalIdle(activeSubIds)
                return
            }

            val target: Int? = findTargetSubId(activeSubIds)
            if (target != null) {
                endCall(target)
            } else {
                handleAmbiguousIdle(activeSubIds)
            }
        }

        @SuppressLint("MissingPermission")
        private fun isDeviceIdle(activeSubIds: List<Int>): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activeSubIds.all { id ->
                    telephonyManager.createForSubscriptionId(id).getCallStateForSubscription() == TelephonyManager.CALL_STATE_IDLE
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
            }

        private suspend fun handleGlobalIdle(activeSubIds: List<Int>) {
            AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions.")
            activeSubIds.forEach { endCall(it, notify = false) }
            dataChangeNotifier.notifyDataChanged()
        }

        private suspend fun handleAmbiguousIdle(activeSubIds: List<Int>) {
            AppLogger.w("Cannot attribute unknown IDLE event for multiple active calls. Using heuristic fallback. Active calls: ${activeSubIds.size}")

            val now = timeProvider.now()
            val staleSessions = activeSubIds.filter { subId ->
                val startTime = callSessionRepository.getCallStartTime(subId).firstWithTimeout() ?: 0L
                startTime > 0 && (now - startTime) > MAX_CALL_DURATION_MS
            }

            if (staleSessions.isNotEmpty()) {
                AppLogger.d("Ending ${staleSessions.size} stale sessions")
                staleSessions.forEach { endCall(it, notify = false) }
                dataChangeNotifier.notifyDataChanged()
            } else {
                fallbackAttribution(activeSubIds)?.let { oldest ->
                    AppLogger.d("Attributing unknown IDLE event to oldest session: $oldest")
                    incrementInaccurateAttributions()
                    endCall(oldest)
                }
            }
        }

        private suspend fun incrementInaccurateAttributions() {
            appSettingsRepository.updateAppSettings { current ->
                current.copy(inaccurateCallAttributionsCount = current.inaccurateCallAttributionsCount + 1)
            }
        }

        @SuppressLint("MissingPermission")
        private fun findTargetSubId(activeSubIds: List<Int>): Int? {
            if (!permissionManager.hasPhoneStatePermission()) return null
            if (activeSubIds.size == 1) {
                return activeSubIds.first()
            }

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val idleSubs = activeSubIds.filter { id ->
                    telephonyManager.createForSubscriptionId(id).getCallStateForSubscription() == TelephonyManager.CALL_STATE_IDLE
                }
                if (idleSubs.size == 1) idleSubs.first() else null
            } else {
                // Fallback for API < 31: If the global state is IDLE, then all subs are IDLE.
                // But this method is usually called when one sub just became IDLE while others might still be active.
                // Without per-subscription state, we can't reliably pick one from many.
                @Suppress("DEPRECATION")
                val isGlobalIdle = telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
                if (isGlobalIdle) {
                    // If everything is idle, we still don't know which one was the LAST to become idle
                    // unless we use the fallback heuristic.
                    null
                } else {
                    null
                }
            }
        }

        private suspend fun fallbackAttribution(activeSubIds: List<Int>): Int? {
            val defaultVoice = permissionManager.getDefaultVoiceSubscriptionId()
            val chosen =
                activeSubIds.firstOrNull { it == defaultVoice }
                    ?: activeSubIds.minByOrNull { callSessionRepository.getCallStartTime(it).firstWithTimeout() ?: 0L }

            AppLogger.d(
                "Call fallback attribution decision: chosen=$chosen, activeSubIds=$activeSubIds, defaultVoice=$defaultVoice. " +
                    "Criteria: defaultVoice match or oldest startTime.",
            )
            return chosen
        }

        private suspend fun endCall(
            subId: Int,
            notify: Boolean = true,
        ) {
            val startTime = callSessionRepository.getCallStartTime(subId).firstWithTimeout() ?: 0L
            val savedBootTime = callSessionRepository.getBootTime(subId).firstWithTimeout() ?: 0L
            val savedUptime = callSessionRepository.getUptime(subId).firstWithTimeout() ?: 0L
            val savedIdentity = callSessionRepository.getCallIdentity(subId).firstWithTimeout()

            val currentBootTime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            if (startTime > 0) {
                if (savedBootTime == 0L || savedBootTime > currentBootTime) {
                    clearSession(subId)
                    AppLogger.d("Stale call session detected (reboot or corrupted)")
                    return
                }

                val durationSeconds = (currentUptime - savedUptime) / MILLIS_IN_SECOND
                clearSession(subId)

                if (durationSeconds > 0) {
                    processCallDuration(durationSeconds.toInt(), subId, savedIdentity, notify)
                }
            }
        }

        private suspend fun refreshStatus(force: Boolean = false) {
            if (!force && _status.value is TrackingStatus.Error) return

            val hasActiveCalls = callSessionRepository.getActiveCallSubIds().isNotEmpty()
            _status.value = if (hasActiveCalls) TrackingStatus.Active else TrackingStatus.Idle
        }

        private suspend fun clearSession(subId: Int) {
            callSessionRepository.setCallStartTime(subId, 0)
            callSessionRepository.setBootTime(subId, 0)
            callSessionRepository.setUptime(subId, 0)
            callSessionRepository.setCallIdentity(subId, null)
        }

        private suspend fun resolveIdentity(subId: Int): String? {
            val subInfoList = permissionManager.getActiveSubscriptions()
            val info = subInfoList.find { it.subscriptionId == subId }
            return if (info != null) {
                simIdentityRepository.getIdentity(info.simSlotIndex)
            } else {
                null
            }
        }

        /**
         * Processes the completed call duration and attributes it to a SIM identity.
         *
         * Rounding logic:
         * Uses ceiling rounding for minutes (1 sec = 1 min, 61 sec = 2 min).
         * This follows the most common carrier billing practices.
         */
        private suspend fun processCallDuration(
            durationSeconds: Int,
            subId: Int,
            savedIdentity: String?,
            notify: Boolean = true,
        ) {
            val durationMinutes = (durationSeconds + SECONDS_IN_MINUTE - 1) / SECONDS_IN_MINUTE
            var targetIdentity = savedIdentity ?: resolveIdentity(subId)

            if (targetIdentity == null) {
                val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()
                if (defaultVoiceSubId != -1) {
                    targetIdentity = resolveIdentity(defaultVoiceSubId)
                }
            }

            if (targetIdentity != null) {
                simUsageRepository.incrementMinutesUsed(targetIdentity, durationMinutes)
                AppLogger.d("Added $durationMinutes min to identity $targetIdentity")
                if (notify) {
                    dataChangeNotifier.notifyDataChanged()
                }
            } else {
                AppLogger.e("Could not attribute call duration for subId $subId")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Attribution failed")
            }
        }

        companion object {
            private const val MILLIS_IN_SECOND = 1000L
            private const val SECONDS_IN_MINUTE = 60
            private const val MAX_CALL_DURATION_MS = 12 * 60 * 60 * 1000L // 12 hours
        }
    }
