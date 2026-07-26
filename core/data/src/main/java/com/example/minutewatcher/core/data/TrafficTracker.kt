package com.example.minutewatcher.core.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.AppLogger
import com.example.minutewatcher.core.model.util.PermissionManager
import com.example.minutewatcher.core.model.util.SimUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks mobile traffic usage for the device.
 *
 * IMPORTANT LIMITATION:
 * Android's NetworkStatsManager (querySummaryForDevice) provides aggregate cellular traffic
 * for the entire device. Since it doesn't always provide reliable per-subscription breakdown
 * for the "Total Mobile" bucket without extra permissions (like READ_NETWORK_USAGE_HISTORY),
 * this tracker attributes all mobile traffic to the CURRENT DEFAULT DATA SIM.
 *
 * If the user swaps the default data SIM, the tracker detects the change and resets the
 * baseline to avoid mixing traffic from different providers.
 */
@Singleton
class TrafficTracker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simRepository: SimRepository,
        private val settingsRepository: SettingsRepository,
        private val permissionManager: PermissionManager,
        @com.example.minutewatcher.core.data.di.ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
        override val key: String = "traffic"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        private val trafficMutex = Mutex()
        private val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

        private val lastDataSubId =
            settingsRepository
                .getLastDataSubId()
                .stateIn(scope, SharingStarted.Eagerly, -1)

        suspend fun resetBaseline(identity: String) {
            trafficMutex.withLock {
                val currentBytes = getCurrentMobileBytes()
                if (currentBytes != -1L) {
                    simRepository.setLastTotalBytes(identity, currentBytes)
                    AppLogger.d("Traffic baseline reset for $identity to $currentBytes")
                }
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
                        settingsRepository.setLastDataSubId(currentDataSubId)
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
                val bucket =
                    networkStatsManager.querySummaryForDevice(
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                        null,
                        0,
                        System.currentTimeMillis(),
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
                targetSim = findSingleActiveSimSlot() ?: -1
            }

            if (targetSim == -1) return null

            // Detect SIM change for baseline reset
            val savedLastDataSubId = lastDataSubId.value
            if (savedLastDataSubId != -1 && currentDataSubId != -1 && savedLastDataSubId != currentDataSubId) {
                AppLogger.d("Default data SIM changed (from $savedLastDataSubId to $currentDataSubId). Resetting baseline.")
                val identity = simRepository.getIdentity(targetSim)
                val currentBytes = getCurrentMobileBytes()
                if (currentBytes != -1L) {
                    simRepository.setLastTotalBytes(identity, currentBytes)
                }
                settingsRepository.setLastDataSubId(currentDataSubId)
                return null // Skip delta for the swap event
            }

            return targetSim to currentDataSubId
        }

        private suspend fun findSingleActiveSimSlot(): Int? {
            val activeSlots = mutableListOf<Int>()

            // We support up to 2 SIM slots
            for (slot in SimConstants.SUPPORTED_SLOT_INDICES) {
                try {
                    val identity = simRepository.getIdentity(slot)
                    val operator = simRepository.getSimOperator(identity).first()

                    if (!SimUtils.isInactiveSim(operator)) {
                        activeSlots.add(slot)
                    }
                } catch (e: Exception) {
                    AppLogger.w("Error checking active status for slot $slot: ${e.message}")
                }
            }

            return if (activeSlots.size == 1) activeSlots.first() else null
        }
    }
