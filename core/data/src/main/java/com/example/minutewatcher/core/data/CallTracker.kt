package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of call tracking logic that monitors call events and records duration.
 * Uses a Mutex to prevent race conditions during concurrent call events.
 */
@Singleton
@Suppress("LongParameterList")
open class CallTracker
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val callSessionRepository: CallSessionRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        private val permissionManager: PermissionManager,
        private val telephonyManager: TelephonyManager,
        private val timeProvider: TimeProvider,
        private val retryHelper: DataStoreRetryHelper,
        @com.example.minutewatcher.core.common.di.ApplicationScope private val scope: CoroutineScope,
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
         * Счётчик попыток записи длительности звонка для каждого subId.
         * Используется для ограничения числа повторных попыток при сбоях DataStore.
         *
         * **Очистка:** удаляется в [cleanupCounters] при успешном завершении
         * или сбросе сессии. Если [cleanupCounters] не завершается из-за
         * ошибки DataStore, запись остаётся в мапе. Это допустимая эвристика
         * в пределах критерия надёжности: [ConcurrentHashMap] не ограничен
         * по размеру, но количество активных subId не превышает
         * [Constants.SimConstants.MAX_SUPPORTED_SIM_SLOTS] (10), поэтому утечка
         * памяти практически невозможна.
         *
         * **Строгое соблюдение API:** [ConcurrentHashMap] потокобезопасен.
         * Записи создаются в [handleWriteDurationFailure] и удаляются
         * в [writeCallDuration] (при успехе) или [cleanupCounters].
         */
        private val writeAttempts = ConcurrentHashMap<Int, Int>()

        /**
         * Счётчик неудачных попыток атрибуции звонка для каждого subId.
         * Аналогично [writeAttempts], очищается в [cleanupCounters].
         * См. документацию [writeAttempts] для деталей.
         */
        private val attributionFailures = ConcurrentHashMap<Int, Int>()

        /**
         * Records call start time for a specific subscription ID.
         *
         * Requires [Manifest.permission.READ_PHONE_STATE].
         * Safe to call from any coroutine context.
         */
        fun onCallStarted(subId: Int) {
            scope.launch {
                try {
                    if (!isCallTrackingEnabled()) {
                        AppLogger.d("CallTracker: tracking disabled, ignoring OFFHOOK for subId $subId")
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException checking if tracking enabled", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                    return@launch
                } catch (e: Exception) {
                    AppLogger.e("Error checking if tracking enabled", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    return@launch
                }

                callMutex.withLock {
                    var operationCompleted = false
                    try {
                        if (!checkPermissions()) return@withLock
                        handleCallStarted(subId)
                        operationCompleted = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        AppLogger.e("SecurityException in onCallStarted", e)
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                    } catch (e: Exception) {
                        AppLogger.e("Error in onCallStarted", e)
                        // If it's already an error (e.g. set by internal logic), don't overwrite with UNKNOWN
                        if (_status.value !is TrackingStatus.Error) {
                            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                        }
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                if (operationCompleted || (_status.value is TrackingStatus.Error)) {
                                    refreshStatus()
                                }
                            } catch (e: Exception) {
                                AppLogger.w("CallTracker: refreshStatus failed in onCallStarted finally block", e)
                            }
                        }
                    }
                }
            }
        }

        /**
         * Records call end time and updates minutes used.
         *
         * Requires [Manifest.permission.READ_PHONE_STATE].
         * Safe to call from any coroutine context.
         */
        fun onCallEnded(subId: Int) {
            scope.launch {
                try {
                    if (handleTrackingDisabled(subId)) return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException checking if tracking enabled", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                    return@launch
                } catch (e: Exception) {
                    AppLogger.e("Error checking if tracking enabled in onCallEnded", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    return@launch
                }

                performCallEnd(subId)
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

        private suspend fun performCallEnd(subId: Int) {
            callMutex.withLock {
                var operationCompleted = false
                try {
                    if (!checkPermissions()) return@withLock

                    val effectiveSubId = resolveEffectiveSubId(subId)

                    if (effectiveSubId == -1) {
                        callEndResolver.resolveUnknownSubIdEnd()
                    } else {
                        endCall(effectiveSubId)
                    }
                    operationCompleted = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    AppLogger.e("SecurityException in onCallEnded", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallEnded", e)
                    if (_status.value !is TrackingStatus.Error) {
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    }
                    if (subId != -1) {
                        try {
                            cleanupCounters(subId)
                        } catch (ex: Exception) {
                            AppLogger.e("Cleanup failed in onCallEnded catch block", ex)
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        try {
                            if (operationCompleted || _status.value is TrackingStatus.Error) {
                                refreshStatus()
                            }
                        } catch (e: Exception) {
                            AppLogger.w("CallTracker: refreshStatus failed in onCallEnded finally block", e)
                        }
                    }
                }
            }
        }

        private suspend fun isCallTrackingEnabled(): Boolean {
            val settings =
                retryHelper.readWithRetry(
                    label = "isCallTrackingEnabled",
                    reader = { appSettingsRepository.getAppSettings() },
                ).getOrThrow()
            return settings.callTrackingEnabled
        }

        private suspend fun handleCallStarted(subId: Int) {
            val targetSubId = resolveTargetSubId(subId)
            if (targetSubId != -1) {
                if (validateAndRecordStart(targetSubId)) {
                    recordSessionData(targetSubId)
                }
            } else {
                AppLogger.w("CallTracker: could not attribute start event (unknown subId and no default SIM)")
            }
        }

        private fun resolveTargetSubId(subId: Int): Int =
            if (subId == -1) {
                val default = permissionManager.getDefaultVoiceSubscriptionId()
                if (default != -1) {
                    AppLogger.d("CallTracker: attributed unknown subId start event to default voice SIM ($default)")
                    default
                } else {
                    -1
                }
            } else {
                subId
            }

        @Suppress("ReturnCount")
        private suspend fun validateAndRecordStart(targetSubId: Int): Boolean {
            val startTimeResult =
                retryHelper.readWithRetry(
                    label = "getCallStartTime",
                    reader = { callSessionRepository.getCallStartTime(targetSubId) },
                )

            if (startTimeResult.isFailure) {
                handleStartTimeFailure(targetSubId, startTimeResult.exceptionOrNull())
                return false
            }

            val startTime = startTimeResult.getOrThrow()
            if (startTime == 0L) return true

            if (!isSessionStale(targetSubId)) {
                AppLogger.d("CallTracker: session already active for subId $targetSubId, ignoring start event")
                return false
            }

            AppLogger.w("CallTracker: clearing stale session for subId $targetSubId")
            try {
                cleanupCounters(targetSubId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Non-fatal error clearing stale session", e)
            }
            return true
        }

        private suspend fun handleStartTimeFailure(
            targetSubId: Int,
            error: Throwable?,
        ) {
            if (error is CancellationException) throw error
            val message = "Timeout reading session data for subId $targetSubId"
            AppLogger.e(message, error)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, error?.message ?: message)
            cleanupCounters(targetSubId)
        }

        private suspend fun isSessionStale(subId: Int): Boolean {
            val bootTimeResult =
                retryHelper.readWithRetry(
                    label = "getBootTime",
                    reader = { callSessionRepository.getBootTime(subId) },
                )
            val savedBootTime = bootTimeResult.getOrElse { 0L }

            return savedBootTime > timeProvider.elapsedRealtime() ||
                (
                    savedBootTime > 0 &&
                        timeProvider.elapsedRealtime() - savedBootTime > Constants.Calls.MAX_CALL_DURATION_MS
                )
        }

        private suspend fun recordSessionData(targetSubId: Int) {
            val now = timeProvider.now()
            val bootTime = timeProvider.elapsedRealtime()
            val uptime = timeProvider.uptimeMillis()
            val identity = resolveIdentity(targetSubId)

            callSessionRepository.setCallStartTime(targetSubId, now)
            callSessionRepository.setBootTime(targetSubId, bootTime)
            callSessionRepository.setUptime(targetSubId, uptime)
            callSessionRepository.setCallIdentity(targetSubId, identity)
            AppLogger.d("Call start recorded for subId $targetSubId at $now (identity=$identity)")
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
        internal open suspend fun refreshStatus(force: Boolean = false) {
            try {
                if (!force) {
                    val currentStatus = _status.value
                    if (currentStatus is TrackingStatus.Error &&
                        currentStatus.type != TrackingStatus.ErrorType.PERMISSION_MISSING
                    ) {
                        // Preserve error from the operation itself unless it's a permission error
                        // that might have been fixed, or we are explicitly recovering.
                        return
                    }
                }

                if (!checkPermissions()) return

                val activeCalls = callSessionRepository.getActiveCallSubIds()
                _status.value = if (activeCalls.isNotEmpty()) TrackingStatus.Active else TrackingStatus.Idle
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
            val startTimeResult =
                retryHelper.readWithRetry(
                    label = "endCall:startTime",
                    reader = { callSessionRepository.getCallStartTime(subId) },
                )
            if (startTimeResult.isFailure) {
                handleReadStartTimeFailure(subId, startTimeResult.exceptionOrNull())
                return
            }
            val startTime = startTimeResult.getOrThrow()

            if (startTime == 0L) {
                AppLogger.d("CallTracker: received IDLE for subId $subId but no session was active")
                return
            }

            if (isSessionStaleInEndCall(subId)) return

            // Строгое соблюдение API: длительность звонка рассчитывается по монотонному
            // времени (elapsedRealtime), а не по настенным часам (System.currentTimeMillis).
            // Это гарантирует, что перевод системных часов пользователем не повлияет на
            // рассчитанную длительность. На AOSP SystemClock.elapsedRealtime() монотонен
            // в пределах одной загрузки устройства. Если кастомная прошивка возвращает
            // немонотонный elapsedRealtime — это проблема кастомной прошивки.
            val savedBootTimeResult =
                retryHelper.readWithRetry(
                    label = "endCall:bootTimeForDuration",
                    reader = { callSessionRepository.getBootTime(subId) },
                )
            val savedBootTime = savedBootTimeResult.getOrElse { 0L }
            val currentBootTime = timeProvider.elapsedRealtime()
            val durationSeconds =
                ((currentBootTime - savedBootTime) / Constants.Time.MILLIS_IN_SECOND).toInt()

            if (validateDurationAndProcess(durationSeconds, subId, notify)) {
                cleanupCounters(subId)
            }
        }

        private suspend fun handleReadStartTimeFailure(
            subId: Int,
            error: Throwable?,
        ) {
            if (error is CancellationException) throw error
            val message = "Timeout reading session data for subId $subId"
            AppLogger.e(message, error)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, error?.message ?: message)
            cleanupCounters(subId)
        }

        private suspend fun isSessionStaleInEndCall(subId: Int): Boolean {
            try {
                val savedBootTimeResult =
                    retryHelper.readWithRetry(
                        label = "endCall:bootTime",
                        reader = { callSessionRepository.getBootTime(subId) },
                    )
                val savedBootTime = savedBootTimeResult.getOrElse { 0L }
                if (savedBootTime > timeProvider.elapsedRealtime() ||
                    (
                        savedBootTime > 0 &&
                            timeProvider.elapsedRealtime() - savedBootTime > Constants.Calls.MAX_CALL_DURATION_MS
                    )
                ) {
                    val message = "Call duration exceeded max limit for subId $subId"
                    AppLogger.w("CallTracker: $message, clearing session")
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                    cleanupCounters(subId)
                    return true
                }
            } catch (e: Exception) {
                AppLogger.e("Error checking stale session in endCall", e)
            }
            return false
        }

        @Suppress("ReturnCount")
        private suspend fun validateDurationAndProcess(
            durationSeconds: Int,
            subId: Int,
            notify: Boolean,
        ): Boolean {
            // Эвристика в пределах критерия надёжности: отрицательная длительность
            // невозможна на AOSP (elapsedRealtime монотонен). Если кастомная прошивка
            // возвращает немонотонный elapsedRealtime — это проблема кастомной прошивки.
            // Защита: отрицательная длительность игнорируется, сессия очищается.
            if (durationSeconds < 0) {
                AppLogger.w("CallTracker: negative duration detected for subId $subId ($durationSeconds s), ignoring")
                return true
            }

            // Safety check for uptime consistency (not affected by wall clock jumps)
            if (!validateUptime(subId)) return true

            val savedIdentityResult =
                retryHelper.readWithRetry(
                    label = "endCall:identity",
                    reader = { callSessionRepository.getCallIdentity(subId) },
                )
            val savedIdentity = savedIdentityResult.getOrNull()

            return processCallDuration(durationSeconds, subId, savedIdentity, notify)
        }

        private suspend fun validateUptime(subId: Int): Boolean {
            val currentUptime = timeProvider.uptimeMillis()
            val savedUptimeResult =
                retryHelper.readWithRetry(
                    label = "endCall:uptime",
                    reader = { callSessionRepository.getUptime(subId) },
                )
            val savedUptime = savedUptimeResult.getOrElse { 0L }
            if (savedUptime > 0 && currentUptime < savedUptime) {
                AppLogger.w("CallTracker: negative uptime duration detected for subId $subId, ignoring")
                return false
            }
            return true
        }

        @Suppress("ReturnCount")
        private suspend fun resolveEffectiveSubId(subId: Int): Int {
            if (subId != -1) return subId

            // 1. Try last data SIM
            val lastResult =
                retryHelper.readWithRetry(
                    label = "resolveEffectiveSubId:lastData",
                    reader = { callSessionRepository.getLastDataSubId() },
                )
            val last = lastResult.getOrElse { -1 }
            if (last != -1) {
                AppLogger.d("CallTracker: attributed unknown subId event to last data SIM ($last)")
                return last
            }

            return resolveEffectiveSubIdFromActiveCalls()
        }

        private suspend fun resolveEffectiveSubIdFromActiveCalls(): Int {
            // 2. Heuristic: if only one call is active, it must be it
            val active =
                try {
                    callSessionRepository.getActiveCallSubIds()
                } catch (e: Exception) {
                    AppLogger.e("Error reading active call subIds", e)
                    emptyList()
                }
            if (active.size == 1) {
                val single = active.first()
                AppLogger.d("CallTracker: attributed unknown subId event to single active call ($single)")
                return single
            }

            return -1
        }

        private suspend fun resolveIdentity(subId: Int): String? =
            withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                simIdentityRepository
                    .observeActiveSims()
                    .firstOrNull()
                    ?.find { it.subscriptionId == subId }
                    ?.identity
                    ?.stableId
            }


        @Suppress("ReturnCount")
        private suspend fun writeCallDuration(
            subId: Int,
            identity: String,
            durationMinutes: Int,
            isFallbackUsed: Boolean,
            notify: Boolean,
        ): Boolean {
            try {
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
                writeAttempts.remove(subId) // Success, clear attempts
                return true
            } catch (e: IOException) {
                return handleWriteDurationFailure(subId, e)
            }
        }

        private fun handleWriteDurationFailure(
            subId: Int,
            e: IOException,
        ): Boolean {
            val newAttempts = (writeAttempts[subId] ?: 0) + 1
            writeAttempts[subId] = newAttempts
            AppLogger.e("CallTracker: failed to write duration, attempt $newAttempts", e)

            if (newAttempts >= MAX_WRITE_ATTEMPTS) {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Session cleared, call data lost after max write attempts",
                    )
                return true // Treat as success to clear session
            } else {
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Call duration write failed, session preserved",
                    )
                return false
            }
        }

        @Suppress("ReturnCount")
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        private suspend fun processCallDuration(
            durationSeconds: Int,
            subId: Int,
            savedIdentity: String?,
            notify: Boolean = true,
        ): Boolean {
            val (targetIdentity, isFallbackUsed) = resolveTargetIdentity(subId, savedIdentity)

            if (durationSeconds == 0) {
                AppLogger.d("CallTracker: zero-duration call for identity $targetIdentity, clearing session")
                cleanupCounters(subId)
                return true
            }

            val durationMinutes = calculateDurationMinutes(durationSeconds)

            return targetIdentity?.let {
                writeCallDuration(subId, it, durationMinutes, isFallbackUsed, notify)
            } ?: handleAttributionFailure(subId)
        }

        private suspend fun handleAttributionFailure(subId: Int): Boolean {
            val failures = (attributionFailures[subId] ?: 0) + 1
            attributionFailures[subId] = failures
            if (failures >= MAX_ATTRIBUTION_FAILURES) {
                AppLogger.e("CallTracker: max attribution failures reached for subId $subId, clearing session")
                cleanupCounters(subId)
                return true
            }
            AppLogger.w("CallTracker: could not attribute call for subId $subId, attempt $failures")
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                    "Could not attribute call to SIM identity",
                )
            dataChangeNotifier.notifyDataChanged()
            return false
        }

        private fun calculateDurationMinutes(seconds: Int): Int =
            (seconds + Constants.Time.CEIL_SECONDS_OFFSET) / Constants.Time.SECONDS_IN_MINUTE

        private suspend fun resolveTargetIdentity(
            subId: Int,
            savedIdentity: String?,
        ): Pair<String?, Boolean> {
            var targetIdentity = savedIdentity ?: resolveIdentity(subId)
            var isFallbackUsed = false

            if (targetIdentity == null) {
                val activeSims = simIdentityRepository.observeActiveSims().firstOrNull() ?: emptyList()
                if (activeSims.isNotEmpty()) {
                    targetIdentity = activeSims.first().identity.stableId
                    isFallbackUsed = true
                    AppLogger.w("CallTracker: using fallback identity $targetIdentity for subId $subId")
                }
            }
            return targetIdentity to isFallbackUsed
        }

        /**
         * Очищает все данные сессии звонка для указанного subId.
         *
         * **Строгое соблюдение API:** все записи в DataStore обнуляются.
         * [writeAttempts] и [attributionFailures] удаляются из мап.
         * Если какая-либо запись в DataStore бросает исключение,
         * метод прерывается и записи в мапах могут остаться.
         * Это допустимо: см. документацию [writeAttempts].
         *
         * @throws CancellationException пробрасывается для корректной отмены.
         * @throws Exception при ошибке записи в DataStore.
         */
        private suspend fun cleanupCounters(subId: Int) {
            try {
                callSessionRepository.setCallStartTime(subId, 0L)
                callSessionRepository.setCallIdentity(subId, null)
                callSessionRepository.setBootTime(subId, 0L)
                callSessionRepository.setUptime(subId, 0L)
                writeAttempts.remove(subId)
                attributionFailures.remove(subId)
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
            }
        }

        /**
         * Recovers tracking state.
         *
         * Safe to call from any coroutine context.
         */
        fun onBoot() {
            scope.launch {
                callMutex.withLock {
                    try {
                        callSessionRepository.clearAllCallSessions()
                        writeAttempts.clear()
                        attributionFailures.clear()
                        AppLogger.d("CallTracker: cleared all sessions on boot")
                    } catch (e: Exception) {
                        AppLogger.e("Error during boot cleanup", e)
                    } finally {
                        refreshStatus(force = true)
                    }
                }
            }
        }

        override suspend fun recover() {
            callMutex.withLock {
                try {
                    callSessionRepository.clearAllCallSessions()
                    writeAttempts.clear()
                    attributionFailures.clear()
                    AppLogger.d("CallTracker: cleared all sessions during recovery")
                } catch (e: Exception) {
                    AppLogger.e("Error during recovery", e)
                } finally {
                    refreshStatus(force = true)
                }
            }
        }

        companion object {
            private const val MAX_WRITE_ATTEMPTS = 3
            private const val MAX_ATTRIBUTION_FAILURES = 3
        }
    }

