package com.example.minutewatcher.core.data

import android.Manifest
import android.content.Context
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
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
         * Счётчик неудачных попыток атрибуции звонка для каждого subId.
         * Очищается в [cleanupCounters].
         */
        private val attributionFailures = ConcurrentHashMap<Int, Int>()

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

            executeCallStart(subId)
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

        private suspend fun executeCallStart(subId: Int) {
            var operationCompleted = false
            try {
                if (!checkPermissions()) return

                val targetSubId = resolveTargetSubId(subId)
                if (targetSubId == -1) {
                    AppLogger.w("CallTracker: could not attribute start event (unknown subId and no default SIM)")
                    return
                }

                val startData = fetchCallStartData(targetSubId)

                callMutex.withLock {
                    processCallStartLocked(targetSubId, startData)
                }
                operationCompleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException in onCallStarted", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            } catch (e: Exception) {
                AppLogger.e("Error in onCallStarted", e)
                if (_status.value !is TrackingStatus.Error) {
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                }
            } finally {
                finalizeCallStart(operationCompleted)
            }
        }

        private suspend fun fetchCallStartData(targetSubId: Int): CallStartData {
            val startTimeResult = retryHelper.readWithRetry(
                label = "getCallStartTime",
                reader = { callSessionRepository.getCallStartTime(targetSubId) },
            )
            val bootTimeResult = retryHelper.readWithRetry(
                label = "getBootTime",
                reader = { callSessionRepository.getBootTime(targetSubId) },
            )
            val identity = resolveIdentity(targetSubId)
            return CallStartData(
                startTimeResult = startTimeResult,
                bootTimeResult = bootTimeResult,
                identity = identity,
                now = timeProvider.now(),
                bootTime = timeProvider.elapsedRealtime(),
                uptime = timeProvider.uptimeMillis(),
            )
        }

        private suspend fun processCallStartLocked(targetSubId: Int, data: CallStartData) {
            if (data.startTimeResult.isFailure) {
                handleStartTimeFailure(targetSubId, data.startTimeResult.exceptionOrNull())
                return
            }

            val startTime = data.startTimeResult.getOrThrow()
            if (startTime != 0L) {
                val savedBootTime = data.bootTimeResult.getOrElse { 0L }
                val isStale = savedBootTime > data.bootTime ||
                    (savedBootTime > 0 && data.bootTime - savedBootTime > Constants.Calls.MAX_CALL_DURATION_MS)

                if (!isStale) {
                    AppLogger.d("CallTracker: session already active for subId $targetSubId, ignoring start event")
                    return
                }

                AppLogger.w("CallTracker: clearing stale session for subId $targetSubId")
                cleanupCounters(targetSubId)
            }

            callSessionRepository.setCallStartTime(targetSubId, data.now)
            callSessionRepository.setBootTime(targetSubId, data.bootTime)
            callSessionRepository.setUptime(targetSubId, data.uptime)
            callSessionRepository.setCallIdentity(targetSubId, data.identity)
            AppLogger.d("Call start recorded for subId $targetSubId at ${data.now} (identity=${data.identity})")
        }

        private suspend fun finalizeCallStart(operationCompleted: Boolean) {
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

            performCallEnd(subId)
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

        private suspend fun performCallEnd(subId: Int) {
            var operationCompleted = false
            try {
                if (!checkPermissions()) return

                val effectiveSubId = resolveEffectiveSubId(subId)

                if (effectiveSubId == -1) {
                    callEndResolver.resolveUnknownSubIdEnd()
                } else {
                    endCall(effectiveSubId)
                }
                operationCompleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
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
                        callMutex.withLock {
                            cleanupCounters(subId)
                        }
                    } catch (ex: Exception) {
                        AppLogger.e("Cleanup failed in onCallEnded catch block", ex)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (operationCompleted && _status.value !is TrackingStatus.Error) {
                            refreshStatus()
                        }
                    } catch (e: Exception) {
                        AppLogger.w("CallTracker: refreshStatus failed in onCallEnded finally block", e)
                    }
                }
            }
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


        @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
        internal open suspend fun refreshStatus(force: Boolean = false) {
            try {
                val currentStatus = _status.value
                if (!force) {
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
            val endData = fetchCallEndData(subId)

            callMutex.withLock {
                processCallEndLocked(subId, endData, notify)
            }
        }

        private suspend fun fetchCallEndData(subId: Int): CallEndData =
            CallEndData(
                startTimeResult = retryHelper.readWithRetry(
                    label = "endCall:startTime",
                    reader = { callSessionRepository.getCallStartTime(subId) },
                ),
                savedBootTimeResult = retryHelper.readWithRetry(
                    label = "endCall:bootTimeForDuration",
                    reader = { callSessionRepository.getBootTime(subId) },
                ),
                savedIdentityResult = retryHelper.readWithRetry(
                    label = "endCall:identity",
                    reader = { callSessionRepository.getCallIdentity(subId) },
                ),
                savedUptimeResult = retryHelper.readWithRetry(
                    label = "endCall:uptime",
                    reader = { callSessionRepository.getUptime(subId) },
                ),
                currentBootTime = timeProvider.elapsedRealtime(),
                currentUptime = timeProvider.uptimeMillis(),
            )

        private suspend fun processCallEndLocked(
            subId: Int,
            data: CallEndData,
            notify: Boolean,
        ) {
            if (data.startTimeResult.isFailure) {
                handleReadStartTimeFailure(subId, data.startTimeResult.exceptionOrNull())
                return
            }
            val startTime = data.startTimeResult.getOrThrow()

            if (startTime == 0L) {
                AppLogger.d("CallTracker: received IDLE for subId $subId but no session was active")
                return
            }

            if (!validateCallDuration(subId, data)) return

            val durationSeconds =
                ((data.currentBootTime - data.savedBootTimeResult.getOrElse { 0L }) / Constants.Time.MILLIS_IN_SECOND)
                    .toInt()

            if (!processCallDurationLocked(subId, durationSeconds, data, notify)) return

            cleanupCounters(subId)
        }

        private suspend fun validateCallDuration(
            subId: Int,
            data: CallEndData,
        ): Boolean {
            val savedBootTime = data.savedBootTimeResult.getOrElse { 0L }
            if (savedBootTime > data.currentBootTime ||
                (savedBootTime > 0 && data.currentBootTime - savedBootTime > Constants.Calls.MAX_CALL_DURATION_MS)
            ) {
                val message = "Call duration exceeded max limit for subId $subId"
                AppLogger.w("CallTracker: $message, clearing session")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                cleanupCounters(subId)
                return false
            }

            if (savedBootTime == 0L) {
                AppLogger.w("CallTracker: bootTime not recorded for subId $subId, clearing session")
                cleanupCounters(subId)
                return false
            }
            return true
        }

        private suspend fun processCallDurationLocked(
            subId: Int,
            durationSeconds: Int,
            data: CallEndData,
            notify: Boolean,
        ): Boolean {
            if (durationSeconds < 0) {
                AppLogger.w("CallTracker: negative duration detected for subId $subId ($durationSeconds s), ignoring")
                cleanupCounters(subId)
                return false
            }

            if (durationSeconds > Constants.Calls.MAX_CALL_DURATION_MS / Constants.Time.MILLIS_IN_SECOND) {
                AppLogger.w("CallTracker: duration $durationSeconds s exceeds max, clearing session for subId $subId")
                cleanupCounters(subId)
                return false
            }

            val savedUptime = data.savedUptimeResult.getOrElse { 0L }
            if (savedUptime > 0 && data.currentUptime < savedUptime) {
                AppLogger.w("CallTracker: negative uptime duration detected for subId $subId, ignoring")
                cleanupCounters(subId)
                return false
            }

            val savedIdentity = data.savedIdentityResult.getOrNull()
            val (targetIdentity, isFallbackUsed) = resolveTargetIdentity(subId, savedIdentity)

            if (durationSeconds == 0) {
                AppLogger.d("CallTracker: zero-duration call for identity $targetIdentity, clearing session")
                cleanupCounters(subId)
                return false
            }

            val durationMinutes = calculateDurationMinutes(durationSeconds)

            return if (targetIdentity != null) {
                writeCallDuration(targetIdentity, durationMinutes, isFallbackUsed, notify)
                true
            } else {
                handleAttributionFailure(subId)
                false
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

        // Округление вверх: 1 секунда -> 1 минута, 61 секунда -> 2 минуты.
        // Соответствует политике биллинга операторов.
        private fun calculateDurationMinutes(seconds: Int): Int =
            (seconds + Constants.Time.SECONDS_IN_MINUTE - 1) / Constants.Time.SECONDS_IN_MINUTE

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
         * [attributionFailures] удаляются из мап.
         * Удаление из мап гарантировано через блок finally даже при сбоях DataStore.
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
                attributionFailures.remove(subId)
            }
        }

        /**
         * Recovers tracking state.
         *
         * Safe to call from any coroutine context.
         */
        fun onBoot() {
            scope.launch {
                try {
                    callMutex.withLock {
                        callSessionRepository.clearAllCallSessions()
                        attributionFailures.clear()
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
                    attributionFailures.clear()
                    AppLogger.d("CallTracker: cleared all sessions during recovery")
                }
            } catch (e: Exception) {
                AppLogger.e("Error during recovery", e)
            } finally {
                refreshStatus(force = true)
            }
        }

        companion object {
            private const val MAX_ATTRIBUTION_FAILURES = 3
        }
    }

private data class CallStartData(
    val startTimeResult: Result<Long>,
    val bootTimeResult: Result<Long>,
    val identity: String?,
    val now: Long,
    val bootTime: Long,
    val uptime: Long,
)

private data class CallEndData(
    val startTimeResult: Result<Long>,
    val savedBootTimeResult: Result<Long>,
    val savedIdentityResult: Result<String?>,
    val savedUptimeResult: Result<Long>,
    val currentBootTime: Long,
    val currentUptime: Long,
)
