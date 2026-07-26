package com.example.minutewatcher.core.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.util.AppLogger
import com.example.minutewatcher.core.model.util.PermissionManager
import com.example.minutewatcher.core.model.util.SimUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficTracker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val simRepository: SimRepository,
    private val settingsRepository: SettingsRepository,
    private val permissionManager: PermissionManager,
    @com.example.minutewatcher.core.data.di.ApplicationScope private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val trafficMutex = Mutex()
    private val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

    private val lastDataSubId = settingsRepository.getLastDataSubId()
        .stateIn(scope, SharingStarted.Eagerly, -1)

    private val operators = (0..1).associateWith { slot ->
        flow {
            val id = simRepository.getIdentity(slot)
            emitAll(simRepository.getSimOperator(id))
        }.stateIn(scope, SharingStarted.Eagerly, "")
    }

    suspend fun resetBaseline(identity: String) {
        trafficMutex.withLock {
            val currentBytes = getCurrentMobileBytes()
            simRepository.setLastTotalBytes(identity, currentBytes)
            AppLogger.d("Traffic baseline reset for $identity to $currentBytes")
        }
    }

    suspend fun updateTrafficUsage() {
        trafficMutex.withLock {
            try {
                val targetInfo = getTargetSimInfo() ?: return
                
                val (targetSim, currentDataSubId) = targetInfo
                val targetIdentity = simRepository.getIdentity(targetSim)
                val currentBytes = getCurrentMobileBytes()
                if (currentBytes == -1L) {
                    AppLogger.d("Traffic stats unavailable, skipping update")
                    if (!permissionManager.hasUsageStatsPermission()) {
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
                    }
                    return
                }

                val lastTotalBytes = simRepository.getLastTotalBytes(targetIdentity).first()

                if (lastTotalBytes == -1L || currentBytes < lastTotalBytes) {
                    // First run or reboot/overflow
                    simRepository.setLastTotalBytes(targetIdentity, currentBytes)
                    AppLogger.d("Traffic baseline initialized/reset for $targetIdentity to $currentBytes")
                    return
                }

                val deltaBytes = currentBytes - lastTotalBytes
                if (deltaBytes.compareTo(0L) > 0) {
                    val deltaMB = deltaBytes.toFloat() / Constants.BYTES_PER_MB
                    if (deltaMB >= 0.0001f) {
                        simRepository.setLastTotalBytes(targetIdentity, currentBytes)
                        simRepository.incrementTrafficUsed(targetIdentity, deltaMB)
                        AppLogger.d("Added $deltaMB MB to identity $targetIdentity (SIM $targetSim)")
                        settingsRepository.setLastDataSubId(currentDataSubId)
                        _status.value = TrackingStatus.Active
                    }
                }
            } catch (e: SecurityException) {
                AppLogger.e("Permission error updating traffic", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
            } catch (e: Exception) {
                AppLogger.e("Error updating traffic", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }
    }

    private fun getCurrentMobileBytes(): Long {
        if (networkStatsManager == null) return -1L
        
        return try {
            val bucket = networkStatsManager.querySummaryForDevice(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                null,
                0,
                System.currentTimeMillis()
            )
            bucket.rxBytes + bucket.txBytes
        } catch (e: SecurityException) {
            AppLogger.e("Permission error in getCurrentMobileBytes", e)
            -1L
        } catch (e: Exception) {
            AppLogger.e("Error querying NetworkStatsManager", e)
            -1L
        }
    }

    private suspend fun getTargetSimInfo(): Pair<Int, Int>? {
        var targetSim = -1
        var currentDataSubId = -1
        try {
            currentDataSubId = permissionManager.getDefaultDataSubscriptionId()
            if (currentDataSubId != -1) {
                val subInfoList = permissionManager.getActiveSubscriptions()
                targetSim = subInfoList.find { it.subscriptionId == currentDataSubId }?.simSlotIndex ?: -1
            }
        } catch (e: Exception) {
            AppLogger.e("Error getting default data SIM", e)
        }

        if (targetSim == -1) {
            val sim0Active = !SimUtils.isInactiveSim(operators[0]?.value ?: "")
            val sim1Active = !SimUtils.isInactiveSim(operators[1]?.value ?: "")
            targetSim = when {
                sim0Active && !sim1Active -> 0
                sim1Active && !sim0Active -> 1
                else -> -1
            }
        }
        
        if (targetSim == -1) return null

        // Проверка смены SIM для пропуска дельты
        val savedLastDataSubId = lastDataSubId.value
        if (savedLastDataSubId != -1 && currentDataSubId != -1 && savedLastDataSubId != currentDataSubId) {
            AppLogger.d("Default data SIM changed, skipping traffic delta")
            settingsRepository.setLastDataSubId(currentDataSubId)
            return null
        }

        return targetSim to currentDataSubId
    }
}
