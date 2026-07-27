package com.example.minutewatcher.core.data

import android.os.SystemClock
import android.telephony.TelephonyManager
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.AppLogger
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallTracker
    @Inject
    constructor(
        private val simRepository: SimRepository,
        private val settingsRepository: SettingsRepository,
        private val updateCoordinator: UpdateCoordinator,
        private val permissionManager: PermissionManager,
        private val telephonyManager: TelephonyManager,
        @com.example.minutewatcher.core.data.di.ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
        override val key: String = "calls"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val callTrackingEnabled =
            settingsRepository
                .getCallTrackingEnabled()
                .stateIn(scope, SharingStarted.Eagerly, false)

        /**
         * Called on device boot to clean up any stale state.
         */
        suspend fun onBoot() {
            settingsRepository.clearAllCallSessions()
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

                val startTime = settingsRepository.getCallStartTime(effectiveSubId).first()
                val savedBootTime = settingsRepository.getBootTime(effectiveSubId).first()
                val currentBootTime = SystemClock.elapsedRealtime()

                val isStale = startTime == 0L || savedBootTime == 0L || savedBootTime > currentBootTime

                if (isStale) {
                    if (startTime != 0L || savedBootTime != 0L) {
                        AppLogger.d("Clearing stale call session for subId $effectiveSubId")
                        clearSession(effectiveSubId)
                    }

                    val identity = resolveIdentity(effectiveSubId)
                    settingsRepository.setCallStartTime(effectiveSubId, System.currentTimeMillis())
                    settingsRepository.setBootTime(effectiveSubId, currentBootTime)
                    settingsRepository.setCallIdentity(effectiveSubId, identity)
                    AppLogger.d("Call started for subId $effectiveSubId (identity: $identity)")
                } else {
                    AppLogger.d("Call already active for subId $effectiveSubId")
                }
            } catch (e: SecurityException) {
                AppLogger.e("Permission error in onCallStarted", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
            } catch (e: Exception) {
                AppLogger.e("Error in onCallStarted", e)
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives IDLE.
         */
        suspend fun onCallEnded(subId: Int) {
            try {
                if (subId == -1) {
                    val activeSubIds = settingsRepository.getActiveCallSubIds()
                    if (activeSubIds.isEmpty()) {
                        AppLogger.d("Call ended with unknown subId and no active sessions found")
                        return
                    }

                    // Priority 1: Global safety cleanup. If device is IDLE, close everything.
                    val isIdle =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
                        } else {
                            @Suppress("DEPRECATION")
                            telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
                        }
                    if (isIdle) {
                        AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions: $activeSubIds")
                        activeSubIds.forEach { endCall(it) }
                        return
                    }

                    // Priority 2: Attribution
                    when {
                        activeSubIds.size == 1 -> {
                            val target = activeSubIds.first()
                            AppLogger.d("Call ended with unknown subId, attributing to single active session: $target")
                            endCall(target)
                        }

                        else -> {
                            val defaultVoice = permissionManager.getDefaultVoiceSubscriptionId()
                            val target =
                                activeSubIds.firstOrNull { it == defaultVoice }
                                    ?: activeSubIds.minByOrNull { settingsRepository.getCallStartTime(it).first() }

                            if (target != null) {
                                AppLogger.d("Call ended with unknown subId, attributing to session: $target")
                                endCall(target)
                            } else {
                                AppLogger.w("Cannot attribute unknown IDLE event for multiple active calls")
                            }
                        }
                    }
                    return
                }

                endCall(subId)
            } catch (e: SecurityException) {
                AppLogger.e("Permission error in onCallEnded", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
            } catch (e: Exception) {
                AppLogger.e("Error in onCallEnded", e)
            }
        }

        private suspend fun endCall(subId: Int) {
            val startTime = settingsRepository.getCallStartTime(subId).first()
            val savedBootTime = settingsRepository.getBootTime(subId).first()
            val savedIdentity = settingsRepository.getCallIdentity(subId).first()
            val currentBootTime = SystemClock.elapsedRealtime()

            if (startTime > 0) {
                // Clear session state first
                clearSession(subId)

                if (!callTrackingEnabled.value) {
                    AppLogger.d("Call tracking disabled, skipping minutes increment for subId $subId")
                    return
                }

                // Validate session against reboots/corruption
                if (savedBootTime == 0L || savedBootTime > currentBootTime) {
                    AppLogger.d("Stale call session detected for subId $subId (reboot or corrupted state)")
                    return
                }

                val durationSeconds = (currentBootTime - savedBootTime) / 1000
                if (durationSeconds > 0) {
                    processCallDuration(durationSeconds.toInt(), subId, savedIdentity)
                }
            }
        }

        private suspend fun clearSession(subId: Int) {
            settingsRepository.setCallStartTime(subId, 0)
            settingsRepository.setBootTime(subId, 0)
            settingsRepository.setCallIdentity(subId, null)
        }

        private suspend fun resolveIdentity(subId: Int): String? {
            val subInfoList = permissionManager.getActiveSubscriptions()
            val info = subInfoList.find { it.subscriptionId == subId }
            return if (info != null) {
                simRepository.getIdentity(info.simSlotIndex)
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
            val durationMinutes = (durationSeconds + 59) / 60

            var targetIdentity = savedIdentity ?: resolveIdentity(subId)

            if (targetIdentity == null) {
                // Fallback to default voice if identity is still unknown
                val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()
                if (defaultVoiceSubId != -1) {
                    targetIdentity = resolveIdentity(defaultVoiceSubId)
                }
            }

            if (targetIdentity != null) {
                simRepository.incrementMinutesUsed(targetIdentity, durationMinutes)
                AppLogger.d("Added $durationMinutes min to identity $targetIdentity for subId $subId")
                updateCoordinator.notifyDataChanged()
                _status.value = TrackingStatus.Active
            } else {
                AppLogger.e("Could not attribute call duration for subId $subId")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Attribution failed")
            }
        }
    }
