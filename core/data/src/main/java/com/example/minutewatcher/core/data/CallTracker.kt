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
class CallTracker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val simRepository: SimRepository,
    private val settingsRepository: SettingsRepository,
    private val updateCoordinator: UpdateCoordinator,
    private val permissionManager: PermissionManager,
    @com.example.minutewatcher.core.data.di.ApplicationScope private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()
    
    private val callTrackingEnabled = settingsRepository.getCallTrackingEnabled()
        .stateIn(scope, SharingStarted.Eagerly, false)
    private val callStartTime = settingsRepository.getCallStartTime()
        .stateIn(scope, SharingStarted.Eagerly, 0L)
    private val bootTime = settingsRepository.getBootTime()
        .stateIn(scope, SharingStarted.Eagerly, 0L)
    private val lastCallSubId = settingsRepository.getLastCallSubId()
        .stateIn(scope, SharingStarted.Eagerly, -1)

    private val operators = (0..1).associateWith { slot ->
        flow {
            val id = simRepository.getIdentity(slot)
            emitAll(simRepository.getSimOperator(id))
        }.stateIn(scope, SharingStarted.Eagerly, "")
    }

    /**
     * Called on device boot to clean up any stale state.
     */
    suspend fun onBoot() {
        settingsRepository.setCallStartTime(0)
        settingsRepository.setBootTime(0)
    }

    /**
     * Called when ACTION_PHONE_STATE_CHANGED receives OFFHOOK.
     */
    suspend fun onCallStarted(subId: Int = -1) {
        if (!callTrackingEnabled.value) return

        val startTime = callStartTime.value
        // If there's already an active call session, don't overwrite it
        if (startTime == 0L) {
            settingsRepository.setCallStartTime(System.currentTimeMillis())
            settingsRepository.setBootTime(SystemClock.elapsedRealtime())
            settingsRepository.setLastCallSubId(subId)
            AppLogger.d("Call started for subId $subId")
        } else {
            val lastSubIdVal = lastCallSubId.value
            if (lastSubIdVal != subId) {
                AppLogger.d("Second line call detected for subId $subId while $lastSubIdVal is active")
            }
        }
    }

    /**
     * Called when ACTION_PHONE_STATE_CHANGED receives IDLE.
     */
    suspend fun onCallEnded(subId: Int = -1) {
        val startTime = callStartTime.value
        val savedBootTime = bootTime.value
        val savedSubId = lastCallSubId.value
        val currentBootTime = SystemClock.elapsedRealtime()

        if (startTime > 0) {
            // Check if this IDLE corresponds to the call we are tracking
            if (subId != -1 && savedSubId != -1 && subId != savedSubId) {
                AppLogger.d("IDLE for subId $subId ignored (tracking subId $savedSubId)")
                return
            }

            // Always clear session state regardless of enabled flag to avoid stale state
            settingsRepository.setCallStartTime(0)
            settingsRepository.setBootTime(0)
            settingsRepository.setLastCallSubId(-1)
            
            if (!callTrackingEnabled.value) {
                AppLogger.d("Call tracking disabled, skipping minutes increment")
                return
            }

            val durationSeconds = (currentBootTime - savedBootTime) / 1000
            if (durationSeconds > 0) {
                val actualSubId = if (subId != -1) subId else savedSubId
                processCallDuration(durationSeconds.toInt(), actualSubId)
            }
        }
    }

    private suspend fun processCallDuration(durationSeconds: Int, subId: Int) {
        // Rounding policy: ceil (any second counts as a full minute)
        val durationMinutes = (durationSeconds + 59) / 60
        
        var targetIdentity: String? = null
        
        if (subId != -1) {
            val subInfoList = permissionManager.getActiveSubscriptions()
            val info = subInfoList.find { it.subscriptionId == subId }
            if (info != null) {
                targetIdentity = simRepository.getIdentity(info.simSlotIndex)
            } else {
                if (!permissionManager.hasPhoneStatePermission()) {
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
                }
            }
        }

        if (targetIdentity == null) {
            val sim0Operator = operators[0]?.value ?: ""
            val sim1Operator = operators[1]?.value ?: ""

            val sim0Active = !SimUtils.isInactiveSim(sim0Operator)
            val sim1Active = !SimUtils.isInactiveSim(sim1Operator)

            val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()
            val subInfoList = permissionManager.getActiveSubscriptions()
            val info = subInfoList.find { it.subscriptionId == defaultVoiceSubId }
            if (info != null) {
                targetIdentity = simRepository.getIdentity(info.simSlotIndex)
            }

            if (targetIdentity == null) {
                targetIdentity = when {
                    sim0Active && !sim1Active -> simRepository.getIdentity(0)
                    sim1Active && !sim0Active -> simRepository.getIdentity(1)
                    sim0Active -> simRepository.getIdentity(0)
                    else -> null
                }
            }
        }

        if (targetIdentity != null) {
            simRepository.incrementMinutesUsed(targetIdentity, durationMinutes)
            AppLogger.d("Added $durationMinutes min to identity $targetIdentity")
            updateCoordinator.notifyDataChanged(context)
            _status.value = TrackingStatus.Active
        }
    }
}
