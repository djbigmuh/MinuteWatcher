package com.example.minutewatcher.core.data

import android.os.Build
import android.telephony.TelephonyManager
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.android.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallTracker
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        appSettingsRepository: AppSettingsRepository,
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

        override fun recover() {
            scope.launch {
                refreshStatus(force = true)
            }
        }

        private val callTrackingEnabled =
            appSettingsRepository
                .getAppSettings()
                .map { it.callTrackingEnabled }
                .stateIn(scope, SharingStarted.Eagerly, false)

        /**
         * Called on device boot to clean up any stale state.
         */
        suspend fun onBoot() {
            callSessionRepository.clearAllCallSessions()
            _status.value = TrackingStatus.Idle
            AppLogger.d("Cleared all stale call sessions on boot")
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives OFFHOOK.
         */
        suspend fun onCallStarted(subId: Int) {
            try {
                if (!callTrackingEnabled.value) return

                val effectiveSubId =
                    if (subId == -1) {
                        permissionManager.getDefaultVoiceSubscriptionId()
                    } else {
                        subId
                    }

                if (effectiveSubId == -1) {
                    AppLogger.d("Call started with unknown subId and no default voice subId, ignoring")
                    return
                }

                val startTime = callSessionRepository.getCallStartTime(effectiveSubId).first()
                val savedBootTime = callSessionRepository.getBootTime(effectiveSubId).first()
                val currentBootTime = timeProvider.elapsedRealtime()
                val currentUptime = timeProvider.uptimeMillis()

                val isStale = startTime == 0L || savedBootTime == 0L || savedBootTime > currentBootTime

                if (isStale) {
                    if (startTime != 0L || savedBootTime != 0L) {
                        AppLogger.d("Clearing stale call session for subId $effectiveSubId")
                        clearSession(effectiveSubId)
                    }

                    val identity = resolveIdentity(effectiveSubId)
                    callSessionRepository.setCallStartTime(effectiveSubId, timeProvider.now())
                    callSessionRepository.setBootTime(effectiveSubId, currentBootTime)
                    callSessionRepository.setUptime(effectiveSubId, currentUptime)
                    callSessionRepository.setCallIdentity(effectiveSubId, identity)
                    _status.value = TrackingStatus.Active
                    AppLogger.d("Call started for subId $effectiveSubId (identity: $identity)")
                } else {
                    AppLogger.d("Call already active for subId $effectiveSubId")
                }
            } catch (e: SecurityException) {
                AppLogger.e("Permission error in onCallStarted", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error in onCallStarted", e)
            } finally {
                refreshStatus()
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives IDLE.
         *
         * If subId is provided (-1), we attempt to attribute the IDLE event to an active session.
         * Heuristic priority:
         * 1. Global state check: if TelephonyManager reports IDLE globally, we close all sessions.
         * 2. Single session check: if only one session is active, attribute to it.
         * 3. Individual state check: query each active subId's state (API 31+).
         * 4. Fallback: attribute to default voice subId or the oldest session.
         */
        suspend fun onCallEnded(subId: Int) {
            try {
                if (subId == -1) {
                    val activeSubIds = callSessionRepository.getActiveCallSubIds()
                    if (activeSubIds.isEmpty()) {
                        AppLogger.d("Call ended with unknown subId and no active sessions found")
                        return
                    }

                    // Priority 1: Global safety cleanup. If device is IDLE, close everything.
                    @Suppress("DEPRECATION")
                    val globalState = telephonyManager.callState
                    val isGlobalIdle = globalState == TelephonyManager.CALL_STATE_IDLE

                    if (isGlobalIdle) {
                        AppLogger.d(
                            "Call ended with unknown subId and global state is IDLE. " +
                                "Closing all sessions: $activeSubIds",
                        )
                        activeSubIds.forEach { endCall(it) }
                        return
                    }

                    // Priority 2: Attribution
                    val target: Int? =
                        when {
                            activeSubIds.size == 1 -> {
                                val single = activeSubIds.first()
                                AppLogger.d("Call ended with unknown subId, attributing to single active session: $single")
                                single
                            }

                            else -> {
                                // Try individual check for API 31+
                                val idleSubs =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        activeSubIds.filter { id ->
                                            val manager = telephonyManager.createForSubscriptionId(id)
                                            @Suppress("DEPRECATION")
                                            manager.callState == TelephonyManager.CALL_STATE_IDLE
                                        }
                                    } else {
                                        emptyList()
                                    }

                                if (idleSubs.size == 1) {
                                    val identified = idleSubs.first()
                                    AppLogger.d("Call ended with unknown subId, identified unique IDLE subId via state check: $identified")
                                    identified
                                } else {
                                    if (idleSubs.isNotEmpty()) {
                                        AppLogger.d("Multiple subIds reported IDLE: $idleSubs. Falling back to heuristic.")
                                    }

                                    val defaultVoice = permissionManager.getDefaultVoiceSubscriptionId()
                                    val fallback =
                                        activeSubIds.firstOrNull { it == defaultVoice }
                                            ?: activeSubIds.minByOrNull { callSessionRepository.getCallStartTime(it).first() }

                                    if (fallback != null) {
                                        AppLogger.d("Call ended with unknown subId, attributed to session via fallback: $fallback (defaultVoice was $defaultVoice)")
                                    }
                                    fallback
                                }
                            }
                        }

                    if (target != null) {
                        endCall(target)
                    } else {
                        AppLogger.w("Cannot attribute unknown IDLE event for multiple active calls: $activeSubIds")
                    }
                    return
                }

                endCall(subId)
            } catch (e: SecurityException) {
                AppLogger.e("Permission error in onCallEnded", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error in onCallEnded", e)
            } finally {
                refreshStatus()
            }
        }

        private suspend fun endCall(subId: Int) {
            val startTime = callSessionRepository.getCallStartTime(subId).first()
            val savedBootTime = callSessionRepository.getBootTime(subId).first()
            val savedUptime = callSessionRepository.getUptime(subId).first()
            val savedIdentity = callSessionRepository.getCallIdentity(subId).first()
            
            val currentBootTime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            if (startTime > 0) {
                // Clear session state first
                clearSession(subId)

                if (!callTrackingEnabled.value) {
                    AppLogger.d("Call tracking disabled, skipping minutes increment for subId $subId")
                    return
                }

                // Validate session against reboots/corruption using elapsedRealtime
                if (savedBootTime == 0L || savedBootTime > currentBootTime) {
                    AppLogger.d("Stale call session detected for subId $subId (reboot or corrupted state)")
                    return
                }

                // Use uptimeMillis for duration calculation (excludes deep sleep)
                val durationSeconds = (currentUptime - savedUptime) / MILLIS_IN_SECOND
                if (durationSeconds > 0) {
                    processCallDuration(durationSeconds.toInt(), subId, savedIdentity)
                }
            }
        }

        private suspend fun refreshStatus(force: Boolean = false) {
            if (!force && _status.value is TrackingStatus.Error) return

            val hasActiveCalls = callSessionRepository.getActiveCallSubIds().isNotEmpty()
            _status.value =
                if (hasActiveCalls) {
                    TrackingStatus.Active
                } else {
                    TrackingStatus.Idle
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

        private suspend fun processCallDuration(
            durationSeconds: Int,
            subId: Int,
            savedIdentity: String?,
        ) {
            // Rounding policy: ceil (any second counts as a full minute)
            val durationMinutes = (durationSeconds + SECONDS_IN_MINUTE - 1) / SECONDS_IN_MINUTE

            var targetIdentity = savedIdentity ?: resolveIdentity(subId)

            if (targetIdentity == null) {
                // Fallback to default voice if identity is still unknown
                val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()
                if (defaultVoiceSubId != -1) {
                    targetIdentity = resolveIdentity(defaultVoiceSubId)
                }
            }

            if (targetIdentity != null) {
                simUsageRepository.incrementMinutesUsed(targetIdentity, durationMinutes)
                AppLogger.d("Added $durationMinutes min to identity $targetIdentity for subId $subId")
                dataChangeNotifier.notifyDataChanged()
            } else {
                AppLogger.e("Could not attribute call duration for subId $subId")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Attribution failed",
                    )
            }
        }

        companion object {
            private const val MILLIS_IN_SECOND = 1000L
            private const val SECONDS_IN_MINUTE = 60
        }
    }
