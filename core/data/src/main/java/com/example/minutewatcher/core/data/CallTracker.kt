package com.example.minutewatcher.core.data

import android.content.Context
import android.os.SystemClock
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.AppLogger
import com.example.minutewatcher.core.model.util.PermissionManager
import com.example.minutewatcher.core.model.util.SimUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallTracker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simRepository: SimRepository,
        private val settingsRepository: SettingsRepository,
        private val updateCoordinator: UpdateCoordinator,
        private val permissionManager: PermissionManager,
        @com.example.minutewatcher.core.data.di.ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val callTrackingEnabled =
            settingsRepository
                .getCallTrackingEnabled()
                .stateIn(scope, SharingStarted.Eagerly, false)

        /**
         * Called on device boot to clean up any stale state.
         * Since we now use subId-based keys, we'd need to know which subIds to clear.
         * For now, we'll clear sessions for slots 0 and 1 as a safety measure,
         * and rely on session validation (bootTime vs currentBootTime).
         */
        suspend fun onBoot() {
            // Best effort cleanup for common slots
            (0..5).forEach { subId ->
                clearSession(subId)
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives OFFHOOK.
         */
        suspend fun onCallStarted(subId: Int) {
            if (!callTrackingEnabled.value) return
            if (subId == -1) {
                AppLogger.d("Call started with unknown subId, ignoring")
                return
            }

            val startTime = settingsRepository.getCallStartTime(subId).first()
            if (startTime == 0L) {
                val identity = resolveIdentity(subId)
                settingsRepository.setCallStartTime(subId, System.currentTimeMillis())
                settingsRepository.setBootTime(subId, SystemClock.elapsedRealtime())
                settingsRepository.setCallIdentity(subId, identity)
                AppLogger.d("Call started for subId $subId (identity: $identity)")
            } else {
                AppLogger.d("Call already active for subId $subId")
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives IDLE.
         */
        suspend fun onCallEnded(subId: Int) {
            if (subId == -1) {
                AppLogger.d("Call ended with unknown subId, checking all active sessions")
                // In some edge cases, subId might be missing in IDLE.
                // We should ideally track which subIds are currently active.
                return
            }

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

                // Validate session against reboots (bootTime should be less than currentBootTime)
                if (savedBootTime > currentBootTime) {
                    AppLogger.d("Stale call session detected for subId $subId (reboot occurred)")
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
                updateCoordinator.notifyDataChanged(context)
                _status.value = TrackingStatus.Active
            } else {
                AppLogger.e("Could not attribute call duration for subId $subId")
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Attribution failed")
            }
        }
    }
