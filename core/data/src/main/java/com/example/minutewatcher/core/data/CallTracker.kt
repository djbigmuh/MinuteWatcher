package com.example.minutewatcher.core.data

import android.annotation.SuppressLint
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private val timeProvider: TimeProvider,
    ) : TrackingStatusProvider {
        override val key: String = "calls"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val callMutex = Mutex()

        private val callEndResolver =
            CallEndResolver(
                callSessionRepository = callSessionRepository,
                permissionManager = permissionManager,
                telephonyManager = telephonyManager,
                timeProvider = timeProvider,
                dataChangeNotifier = dataChangeNotifier,
                endCall = { subId, notify -> endCall(subId, notify) },
                clearStaleSession = { subId -> clearStaleSession(subId) },
                incrementInaccurateAttributions = { incrementInaccurateAttributions() },
            )

        override suspend fun recover() {
            callMutex.withLock {
                _status.value = TrackingStatus.Idle
                if (checkPermissions()) {
                    refreshStatus(force = true)
                }
            }
        }

        private suspend fun isCallTrackingEnabled(): Boolean =
            appSettingsRepository.getAppSettings().firstWithTimeout()?.callTrackingEnabled
                ?: run {
                    // Default to true: if settings are temporarily unreadable, it is safer
                    // to track a call (matching the AppSettings default) than to silently lose data.
                    true
                }

        /**
         * Called on device boot to clean up any stale state.
         */
        suspend fun onBoot() {
            callMutex.withLock {
                val activeSessions = callSessionRepository.getActiveCallSubIds()
                callSessionRepository.clearAllCallSessions()
                _status.value = TrackingStatus.Idle
                AppLogger.d("Cleared ${activeSessions.size} stale call sessions on boot")
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives OFFHOOK.
         */
        suspend fun onCallStarted(subId: Int) {
            callMutex.withLock {
                var justStarted = false
                try {
                    if (!isCallTrackingEnabled() || !checkPermissions()) return@withLock

                    val effectiveSubId = resolveEffectiveSubId(subId)
                    if (effectiveSubId == -1) return@withLock

                    val isNewSessionStarted = handleCallStartInternal(effectiveSubId)
                    if (isNewSessionStarted) {
                        justStarted = true
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
                            if ((_status.value !is TrackingStatus.Error) && !justStarted) {
                                refreshStatus()
                            }
                        } catch (e: Exception) {
                            AppLogger.w("CallTracker: refreshStatus failed in onCallStarted finally block", e)
                        }
                    }
                }
            }
        }

        private fun resolveEffectiveSubId(subId: Int): Int {
            val effective =
                if (subId == -1) {
                    permissionManager.getDefaultVoiceSubscriptionId()
                } else {
                    subId
                }

            if (effective == -1) {
                AppLogger.d("Call started with unknown subId and no default voice subId, ignoring")
            }
            return effective
        }

        /**
         * Reads a value from a Flow with a specified number of attempts and timeout.
         * Uses exponential backoff between retries to increase reliability when DataStore
         * or other reactive sources are temporarily under high load or locked.
         */
        private suspend fun <T : Any> readWithRetry(
            attempts: Int = 2,
            timeoutMs: Long = Constants.Flow.SHORT_TIMEOUT_MS,
            retryDelayMs: Long = 500L,
            reader: () -> Flow<T>,
        ): Result<T> {
            var currentDelay = retryDelayMs
            for (attempt in 1..attempts) {
                val result =
                    withTimeoutOrNull(timeoutMs.milliseconds) {
                        reader().first()
                    }
                result?.let { return Result.success(it) }
                if (attempt < attempts) {
                    AppLogger.d("readWithRetry: attempt $attempt timed out, retrying in ${currentDelay}ms")
                    delay(currentDelay.milliseconds)
                    currentDelay *= 2
                }
            }
            AppLogger.w("readWithRetry: all $attempts attempts timed out")
            return Result.failure(java.util.concurrent.TimeoutException("readWithRetry timed out"))
        }

        private suspend fun handleCallStartInternal(subId: Int): Boolean {
            val startTimeResult =
                readWithRetry(
                    attempts = 2,
                    timeoutMs = 3000,
                    retryDelayMs = 500,
                ) { callSessionRepository.getCallStartTime(subId) }
            val savedBootTimeResult =
                readWithRetry(
                    attempts = 2,
                    timeoutMs = 3000,
                    retryDelayMs = 500,
                ) { callSessionRepository.getBootTime(subId) }

            if (startTimeResult.isFailure || savedBootTimeResult.isFailure) {
                val field = if (startTimeResult.isFailure) "startTime" else "bootTime"
                AppLogger.e("Call start event dropped due to DataStore timeout for subId $subId ($field)", null)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading $field for subId $subId",
                    )
                try {
                    clearSession(subId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Failed to clear session for subId $subId after start timeout", e)
                }
                return false
            }

            val startTime = startTimeResult.getOrThrow()
            val savedBootTime = savedBootTimeResult.getOrThrow()
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
                return true
            } else {
                AppLogger.d("Duplicate OFFHOOK for subId $subId, session already active, skipping")
                return false
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives IDLE.
         */
        suspend fun onCallEnded(subId: Int) {
            callMutex.withLock {
                var errorSetInThisCall = false
                try {
                    executeCallEnded(subId)
                    if (_status.value is TrackingStatus.Error) {
                        // Фиксируем, что была установлена ошибка (например, провал атрибуции),
                        // чтобы блок finally не сбросил её в Idle/Active через refreshStatus().
                        errorSetInThisCall = true
                    }
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                    errorSetInThisCall = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallEnded", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    errorSetInThisCall = true
                } finally {
                    withContext(NonCancellable) {
                        try {
                            if (!errorSetInThisCall) {
                                refreshStatus()
                            }
                        } catch (e: Exception) {
                            AppLogger.w("CallTracker: refreshStatus failed in onCallEnded finally block", e)
                        }
                    }
                }
            }
        }

        private suspend fun executeCallEnded(subId: Int) {
            if (!checkPermissions()) return

            if (!isCallTrackingEnabled()) {
                AppLogger.d("Call tracking disabled, cleaning up session for subId $subId")
                if (subId != -1) clearSession(subId)
                return
            }

            if (subId == -1) {
                try {
                    callEndResolver.resolveUnknownSubIdEnd()
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                }
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
            } else if (_status.value is TrackingStatus.Error) {
                _status.value = TrackingStatus.Idle
            }

            return error == null
        }

        private fun handleSecurityException(e: SecurityException) {
            AppLogger.e("SecurityException in CallTracker", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
        }

        private suspend fun incrementInaccurateAttributions() {
            appSettingsRepository.updateAppSettings { current ->
                current.copy(inaccurateCallAttributionsCount = current.inaccurateCallAttributionsCount + 1)
            }
        }

        private suspend fun clearStaleSession(subId: Int) {
            loadCallSessionData(subId)
            clearSession(subId)
            AppLogger.d("Cleared stale call session for subId $subId without adding minutes")
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

                val now = timeProvider.now()
                if ((now - sessionData.startTime) > Constants.Calls.MAX_CALL_DURATION_MS) {
                    // Проверку на "протухшую" сессию (stale) выполняем ДО попытки атрибуции.
                    // Это гарантирует очистку сессии даже при ошибке записи минут.
                    AppLogger.w(
                        "Call attribution failed for subId $subId and session exceeded " +
                            "${Constants.Calls.MAX_CALL_DURATION_MS / Constants.MILLIS_PER_HOUR} hours. " +
                            "Force clearing session.",
                    )
                    clearSession(subId)
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Attribution failed for subId $subId",
                        )
                    return
                }

                val durationSeconds = (currentUptime - sessionData.savedUptime) / Constants.MILLIS_PER_SECOND
                if (durationSeconds < 0) {
                    AppLogger.w(
                        "Negative call duration detected for subId $subId " +
                            "(currentUptime=$currentUptime, savedUptime=${sessionData.savedUptime}). Clearing session.",
                    )
                    clearSession(subId)
                    return
                }

                val success =
                    processCallDuration(
                        durationSeconds = durationSeconds.toInt(),
                        subId = subId,
                        savedIdentity = sessionData.savedIdentity,
                        savedUptime = sessionData.savedUptime,
                        notify = notify,
                    )

                if (success) {
                    clearSession(subId)
                } else {
                    AppLogger.e(
                        "Call duration write failed for subId $subId (duration: $durationSeconds s), " +
                            "session preserved",
                    )
                }
            }
        }

        private data class CallSessionData(
            val startTime: Long,
            val savedBootTime: Long,
            val savedUptime: Long,
            val savedIdentity: String?,
        )

        /**
         * Wrapper to handle nullable values in [readWithRetry].
         */
        private data class Box<out T>(
            val value: T,
        )

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        private suspend fun loadCallSessionData(subId: Int): CallSessionData? {
            val startTimeResult = readSessionField(subId) { callSessionRepository.getCallStartTime(it) }
            val savedBootTimeResult = readSessionField(subId) { callSessionRepository.getBootTime(it) }
            val savedUptimeResult = readSessionField(subId) { callSessionRepository.getUptime(it) }
            val savedIdentityBoxResult =
                readSessionField(subId) {
                    callSessionRepository.getCallIdentity(it).map { id -> Box(id) }
                }

            val isAnyLoadFailed =
                startTimeResult.isFailure ||
                    savedBootTimeResult.isFailure ||
                    savedUptimeResult.isFailure ||
                    savedIdentityBoxResult.isFailure

            if (isAnyLoadFailed) {
                val field =
                    when {
                        startTimeResult.isFailure -> "startTime"
                        savedBootTimeResult.isFailure -> "savedBootTime"
                        savedUptimeResult.isFailure -> "savedUptime"
                        else -> "savedIdentity"
                    }
                AppLogger.e("Call session data read timeout for subId $subId ($field), clearing session", null)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout loading session data for subId $subId",
                    )
                // Очищаем сессию, чтобы избежать stale-данных
                try {
                    clearSession(subId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Failed to clear stale session for subId $subId after read timeout", e)
                }
                return null
            }

            return CallSessionData(
                startTime = startTimeResult.getOrThrow(),
                savedBootTime = savedBootTimeResult.getOrThrow(),
                savedUptime = savedUptimeResult.getOrThrow(),
                savedIdentity = savedIdentityBoxResult.getOrThrow().value,
            )
        }

        private suspend fun <T : Any> readSessionField(
            subId: Int,
            block: (Int) -> kotlinx.coroutines.flow.Flow<T>,
        ): Result<T> =
            readWithRetry(
                attempts = 2,
                timeoutMs = 5000,
                retryDelayMs = 1000,
            ) { block(subId) }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
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

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        private suspend fun refreshStatus(force: Boolean = false) {
            val current = _status.value
            val isIgnoredError =
                current is TrackingStatus.Error &&
                    !force &&
                    (
                        current.type == TrackingStatus.ErrorType.UNKNOWN ||
                            current.type == TrackingStatus.ErrorType.PERMISSION_MISSING
                    )

            if (!isIgnoredError) {
                val hasActiveCalls = callSessionRepository.getActiveCallSubIds().isNotEmpty()
                _status.value = if (hasActiveCalls) TrackingStatus.Active else TrackingStatus.Idle
            }
        }

        private suspend fun clearSession(subId: Int) {
            callSessionRepository.setCallStartTime(subId, 0L)
            callSessionRepository.setBootTime(subId, 0L)
            callSessionRepository.setUptime(subId, 0L)
            callSessionRepository.setCallIdentity(subId, null)
        }

        private suspend fun resolveIdentity(subId: Int): String? {
            val subInfoList = permissionManager.getActiveSubscriptions()
            val info = subInfoList.find { it.subscriptionId == subId }
            return info?.let { simIdentityRepository.getIdentity(it.simSlotIndex) }
        }

        /**
         * Processes the completed call duration and attributes it to a SIM identity.
         *
         * Rounding logic:
         * Uses ceiling rounding for minutes (1 sec = 1 min, 61 sec = 2 min).
         * This follows the most common carrier billing practices.
         */
        @Suppress("ReturnCount")
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        private suspend fun processCallDuration(
            durationSeconds: Int,
            subId: Int,
            savedIdentity: String?,
            savedUptime: Long,
            notify: Boolean = true,
        ): Boolean {
            if (durationSeconds < 0) {
                AppLogger.w(
                    "CallTracker: negative call duration detected in processCallDuration " +
                        "(duration=$durationSeconds, savedUptime=$savedUptime). Skipping update.",
                )
                return false
            }
            if (durationSeconds == 0) return true
            if (!isCallTrackingEnabled()) {
                AppLogger.d("Call tracking disabled, skipping duration recording for subId $subId")
                return true
            }
            val durationMinutes =
                (
                    (durationSeconds.toLong() + Constants.SECONDS_PER_MINUTE - 1) /
                        Constants.SECONDS_PER_MINUTE
                ).toInt()
            var targetIdentity = savedIdentity ?: resolveIdentity(subId)

            if (targetIdentity == null) {
                val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()
                if (defaultVoiceSubId != -1) {
                    targetIdentity = resolveIdentity(defaultVoiceSubId)
                }
            }

            if (targetIdentity != null) {
                return try {
                    simUsageRepository.incrementMinutesUsed(targetIdentity, durationMinutes)
                    AppLogger.d("Added $durationMinutes min to identity $targetIdentity")
                    if (notify) {
                        dataChangeNotifier.notifyDataChanged()
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e(
                        "CRITICAL: Lost $durationMinutes min for identity $targetIdentity (subId=$subId). " +
                            "DataStore write failed.",
                        e,
                    )
                    _status.value =
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "Call duration write failed, session preserved",
                        )
                    false
                }
            } else {
                AppLogger.e("Could not attribute call duration for subId $subId")
                _status.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Attribution failed for subId $subId")
                return false
            }
        }

        companion object
    }

/**
 * Вспомогательный класс для разрешения завершения звонков в неопределенных ситуациях.
 * Вынесен из [CallTracker] для уменьшения количества методов в основном классе.
 */
internal class CallEndResolver(
    private val callSessionRepository: CallSessionRepository,
    private val permissionManager: PermissionManager,
    private val telephonyManager: TelephonyManager,
    private val timeProvider: TimeProvider,
    private val dataChangeNotifier: DataChangeNotifier,
    private val endCall: suspend (Int, Boolean) -> Unit,
    private val clearStaleSession: suspend (Int) -> Unit,
    private val incrementInaccurateAttributions: suspend () -> Unit,
) {
    suspend fun resolveUnknownSubIdEnd() {
        if (!permissionManager.hasPhoneStatePermission()) {
            AppLogger.w("CallEndResolver: permission revoked during resolveUnknownSubIdEnd")
            return
        }
        val activeSubIds =
            try {
                callSessionRepository.getActiveCallSubIds()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to read active call sessions on unknown subId end", e)
                emptyList()
            }

        if (activeSubIds.isEmpty()) {
            AppLogger.d("Call ended with unknown subId and no active sessions found")
        } else if (isDeviceIdle(activeSubIds)) {
            handleGlobalIdle(activeSubIds)
        } else {
            val target: Int? = findTargetSubId(activeSubIds)
            if (target != null) {
                endCall(target, true)
            } else {
                handleAmbiguousIdle(activeSubIds)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceIdle(activeSubIds: List<Int>): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeSubIds.all { id ->
                if (!permissionManager.hasPhoneStatePermission()) return@all true
                telephonyManager.createForSubscriptionId(id).callStateForSubscription ==
                    TelephonyManager.CALL_STATE_IDLE
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
        }

    private suspend fun handleGlobalIdle(activeSubIds: List<Int>) {
        AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions.")
        for (subId in activeSubIds) {
            endCall(subId, false)
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
            AppLogger.d("Clearing ${staleSessions.size} stale sessions")
            for (subId in staleSessions) {
                clearStaleSession(subId)
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
                endCall(oldest, false)
                dataChangeNotifier.notifyDataChanged()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findTargetSubId(activeSubIds: List<Int>): Int? {
        if (!permissionManager.hasPhoneStatePermission()) return null

        return when {
            activeSubIds.size == 1 -> activeSubIds.first()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                activeSubIds
                    .asSequence()
                    .filter { id ->
                        if (!permissionManager.hasPhoneStatePermission()) return@filter false
                        telephonyManager.createForSubscriptionId(id).callStateForSubscription ==
                            TelephonyManager.CALL_STATE_IDLE
                    }.singleOrNull()
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
                    val t = callSessionRepository.getCallStartTime(subId).firstWithTimeout() ?: Long.MAX_VALUE
                    if (t < minTime) {
                        minTime = t
                        minSubId = subId
                    }
                }
                if (minTime == Long.MAX_VALUE) {
                    AppLogger.w(
                        "fallbackAttribution: all callStartTime reads timed out, " +
                            "cannot attribute IDLE event",
                    )
                    return null
                }
                minSubId
            }

        AppLogger.d(
            "Call fallback attribution decision: chosen=$chosen, activeSubIds=$activeSubIds, " +
                "defaultVoice=$defaultVoice. Criteria: defaultVoice match or oldest startTime.",
        )
        return chosen
    }
}
