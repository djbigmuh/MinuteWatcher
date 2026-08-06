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
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

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
                if (checkPermissions()) {
                    _status.value = TrackingStatus.Idle
                    refreshStatus(force = true)
                }
            }
        }

        private suspend fun isCallTrackingEnabled(): Boolean =
            appSettingsRepository.getAppSettings().firstWithTimeout()?.callTrackingEnabled ?: false

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

                    val effectiveSubId =
                        if (subId == -1) {
                            permissionManager.getDefaultVoiceSubscriptionId()
                        } else {
                            subId
                        }

                    if (effectiveSubId == -1) {
                        AppLogger.d("Call started with unknown subId and no default voice subId, ignoring")
                        return@withLock
                    }

                    if (handleCallStartInternal(effectiveSubId)) {
                        _status.value = TrackingStatus.Active
                    }
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallStarted", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                } finally {
                    withContext(NonCancellable) {
                        try {
                            refreshStatus()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        private suspend fun handleCallStartInternal(subId: Int): Boolean {
            val startTimeRes = readWithRetry({ callSessionRepository.getCallStartTime(subId) }, "startTime", subId)
            val savedBootTimeRes = readWithRetry({ callSessionRepository.getBootTime(subId) }, "bootTime", subId)

            val failure =
                startTimeRes.exceptionOrNull()
                    ?: savedBootTimeRes.exceptionOrNull()
            if (failure != null) {
                _status.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, failure.message ?: "Unknown error")
                return false
            }

            val startTime = startTimeRes.getOrThrow()
            val savedBootTime = savedBootTimeRes.getOrThrow()
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
            return true
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
                    withContext(NonCancellable) {
                        try {
                            refreshStatus()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        private suspend fun executeCallEnded(subId: Int) {
            if (!checkPermissions()) return

            if (subId == -1) {
                handleUnknownSubIdEnd()
            } else {
                endCall(subId)
            }
        }

        private fun checkPermissions(): Boolean {
            val error =
                when {
                    !permissionManager.isTelephonySupported() -> {
                        AppLogger.w("CallTracker: telephony not supported")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "SubscriptionManager unavailable",
                        )
                    }

                    !permissionManager.hasPhoneStatePermission() -> {
                        AppLogger.w("CallTracker: phone state permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Phone State missing",
                        )
                    }

                    else -> null
                }

            if (error != null) {
                _status.value = error
            }

            return error == null
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
            } else if (isDeviceIdle(activeSubIds)) {
                handleGlobalIdle(activeSubIds)
            } else {
                val target: Int? = findTargetSubId(activeSubIds)
                if (target != null) {
                    endCall(target)
                } else {
                    handleAmbiguousIdle(activeSubIds)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun isDeviceIdle(activeSubIds: List<Int>): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activeSubIds.all { id ->
                    telephonyManager.createForSubscriptionId(id).getCallStateForSubscription() ==
                        TelephonyManager.CALL_STATE_IDLE
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
            }

        private suspend fun handleGlobalIdle(activeSubIds: List<Int>) {
            AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions.")
            for (subId in activeSubIds) {
                endCall(subId, notify = false)
            }
            dataChangeNotifier.notifyDataChanged()
        }

        private suspend fun handleAmbiguousIdle(activeSubIds: List<Int>) {
            AppLogger.w(
                "Cannot attribute unknown IDLE event for multiple active calls. " +
                    "Using heuristic fallback. Active calls: ${activeSubIds.size}",
            )

            val now = timeProvider.now()
            val staleSessions = mutableListOf<Int>()
            for (subId in activeSubIds) {
                val startTime = callSessionRepository.getCallStartTime(subId).firstWithTimeout() ?: 0L
                if (startTime > 0 && (now - startTime) > Constants.Calls.MAX_CALL_DURATION_MS) {
                    staleSessions.add(subId)
                }
            }

            if (staleSessions.isNotEmpty()) {
                AppLogger.d("Ending ${staleSessions.size} stale sessions")
                for (subId in staleSessions) {
                    endCall(subId, notify = false)
                }
                dataChangeNotifier.notifyDataChanged()
            } else {
                fallbackAttribution(activeSubIds)?.let { oldest ->
                    AppLogger.d("Attributing unknown IDLE event to oldest session: $oldest")
                    // On API < 31, we don't have per-subscription state, so "ambiguity" is expected
                    // and not necessarily an "inaccuracy" in app logic.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        incrementInaccurateAttributions()
                    }
                    endCall(oldest, notify = false)
                    dataChangeNotifier.notifyDataChanged()
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
            if (activeSubIds.size == 1) return activeSubIds.first()

            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val idleSubs =
                        activeSubIds.filter { id ->
                            telephonyManager.createForSubscriptionId(id).getCallStateForSubscription() ==
                                TelephonyManager.CALL_STATE_IDLE
                        }
                    if (idleSubs.size == 1) idleSubs.first() else null
                }
                else -> null
            }
        }

        private suspend fun fallbackAttribution(activeSubIds: List<Int>): Int? {
            val defaultVoice = permissionManager.getDefaultVoiceSubscriptionId()

            val chosen =
                activeSubIds.firstOrNull { it == defaultVoice } ?: run {
                    var minSubId: Int? = null
                    var minTime = Long.MAX_VALUE
                    for (subId in activeSubIds) {
                        val t = callSessionRepository.getCallStartTime(subId).firstWithTimeout() ?: 0L
                        if (t < minTime) {
                            minTime = t
                            minSubId = subId
                        }
                    }
                    minSubId
                }

            AppLogger.d(
                "Call fallback attribution decision: chosen=$chosen, activeSubIds=$activeSubIds, " +
                    "defaultVoice=$defaultVoice. Criteria: defaultVoice match or oldest startTime.",
            )
            return chosen
        }

        private suspend fun endCall(
            subId: Int,
            notify: Boolean = true,
        ) {
            val sessionData = loadCallSessionData(subId) ?: return

            val currentBootTime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            if (sessionData.startTime > 0) {
                if (!validateSession(subId, sessionData, currentBootTime)) {
                    return
                }

                val durationSeconds = (currentUptime - sessionData.savedUptime) / MILLIS_IN_SECOND
                clearSession(subId)

                if (durationSeconds > 0) {
                    processCallDuration(durationSeconds.toInt(), subId, sessionData.savedIdentity, notify)
                }
            }
        }

        private data class CallSessionData(
            val startTime: Long,
            val savedBootTime: Long,
            val savedUptime: Long,
            val savedIdentity: String?,
        )

        private suspend fun loadCallSessionData(subId: Int): CallSessionData? {
            val startTimeRes = readWithRetry({ callSessionRepository.getCallStartTime(subId) }, "startTime", subId)
            val savedBootTimeRes = readWithRetry({ callSessionRepository.getBootTime(subId) }, "bootTime", subId)
            val savedUptimeRes = readWithRetry({ callSessionRepository.getUptime(subId) }, "uptime", subId)
            val savedIdentityRes = readWithRetry({ callSessionRepository.getCallIdentity(subId) }, "identity", subId)

            val failure =
                startTimeRes.exceptionOrNull()
                    ?: savedBootTimeRes.exceptionOrNull()
                    ?: savedUptimeRes.exceptionOrNull()
                    ?: savedIdentityRes.exceptionOrNull()

            if (failure != null) {
                _status.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, failure.message ?: "Unknown error")
                // Очищаем сессию, чтобы избежать stale-данных
                clearSession(subId)
                return null
            }

            return CallSessionData(
                startTime = startTimeRes.getOrThrow(),
                savedBootTime = savedBootTimeRes.getOrThrow(),
                savedUptime = savedUptimeRes.getOrThrow(),
                savedIdentity = savedIdentityRes.getOrThrow(),
            )
        }

        private suspend fun validateSession(
            subId: Int,
            data: CallSessionData,
            currentBootTime: Long,
        ): Boolean {
            val isMissingBootTime = data.savedBootTime == 0L
            val isStaleSession = data.savedBootTime > currentBootTime

            if (isMissingBootTime) {
                AppLogger.w("Call session for subId $subId has no boot time (possible write failure). Discarding.")
            }
            if (isStaleSession) {
                AppLogger.d("Stale call session detected for subId $subId (reboot). Clearing.")
            }

            val isValid = !isMissingBootTime && !isStaleSession
            if (!isValid) {
                clearSession(subId)
            }
            return isValid
        }

        private suspend fun refreshStatus(force: Boolean = false) {
            val current = _status.value
            val isIgnoredError =
                current is TrackingStatus.Error &&
                    !force &&
                    (current.type == TrackingStatus.ErrorType.UNKNOWN ||
                        current.type == TrackingStatus.ErrorType.PERMISSION_MISSING)

            if (!isIgnoredError) {
                val hasActiveCalls = callSessionRepository.getActiveCallSubIds().isNotEmpty()
                _status.value = if (hasActiveCalls) TrackingStatus.Active else TrackingStatus.Idle
            }
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
            if (!isCallTrackingEnabled()) {
                AppLogger.d("Call tracking disabled, skipping duration recording for subId $subId")
                return
            }
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
                _status.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Attribution failed for subId $subId")
            }
        }

        private suspend fun <T> readWithRetry(
            flowProvider: () -> kotlinx.coroutines.flow.Flow<T>,
            fieldName: String,
            subId: Int,
        ): Result<T> {
            suspend fun tryRead(timeout: Long): Result<T>? =
                try {
                    withTimeoutOrNull(timeout.milliseconds) {
                        Result.success(flowProvider().first())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }

            val firstResult = tryRead(READ_RETRY_SHORT_TIMEOUT_MS)
            if (firstResult != null && firstResult.isSuccess) return firstResult

            val reason = if (firstResult == null) "Timeout" else "Error (${firstResult.exceptionOrNull()?.message})"
            AppLogger.w("CallTracker: $reason reading $fieldName for subId $subId, retrying with 10s...")

            val secondResult = tryRead(READ_RETRY_LONG_TIMEOUT_MS)
            return secondResult ?: Result.failure(TimeoutException("Timeout reading $fieldName"))
        }

        companion object {
            private const val MILLIS_IN_SECOND = 1000L
            private const val SECONDS_IN_MINUTE = 60
            private const val READ_RETRY_SHORT_TIMEOUT_MS = 5_000L
            private const val READ_RETRY_LONG_TIMEOUT_MS = 10_000L
        }
    }
