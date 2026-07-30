package com.example.minutewatcher.core.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimConstants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.android.util.PermissionManager
import com.example.minutewatcher.core.android.util.SimUtils
import com.example.minutewatcher.core.common.util.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val callSessionRepository: CallSessionRepository,
        private val permissionManager: PermissionManager,
        private val timeProvider: TimeProvider,
        @ApplicationScope private val scope: CoroutineScope,
    ) : TrackingStatusProvider {
        override val key: String = "traffic"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        override fun recover() {
            scope.launch {
                updateTrafficUsage()
            }
        }

        private val trafficMutex = Mutex()
        private val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

        private var inMemoryLastDataSubId: Int = -1

        suspend fun resetBaseline(identity: String) {
            trafficMutex.withLock {
                try {
                    val currentBytes = getCurrentMobileBytes()
                    if (currentBytes != -1L) {
                        simUsageRepository.setLastTotalBytes(identity, currentBytes)
                        AppLogger.d("Traffic baseline reset for $identity to $currentBytes")
                        _status.value = TrackingStatus.Active
                    } else {
                        _status.value = TrackingStatus.Idle
                    }
                } catch (e: SecurityException) {
                    AppLogger.e("Permission error in resetBaseline", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in resetBaseline", e)
                }
            }
        }

        suspend fun updateTrafficUsage() {
            trafficMutex.withLock {
                try {
                    val targetInfo = getTargetSimInfo()
                    if (targetInfo == null) {
                        _status.value = TrackingStatus.Idle
                        return@withLock
                    }

                    val (targetSim, currentDataSubId) = targetInfo
                    val targetIdentity = simIdentityRepository.getIdentity(targetSim)
                    val currentBytes = getCurrentMobileBytes()
                    if (currentBytes == -1L) {
                        AppLogger.d("Traffic stats unavailable, skipping update")
                        if (!permissionManager.hasUsageStatsPermission()) {
                            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
                        } else {
                            _status.value = TrackingStatus.Idle
                        }
                        return@withLock
                    }

                    val lastTotalBytes = simUsageRepository.getLastTotalBytes(targetIdentity).first()

                    if (lastTotalBytes == -1L || currentBytes < lastTotalBytes) {
                        // First run or reboot/overflow
                        simUsageRepository.setLastTotalBytes(targetIdentity, currentBytes)
                        AppLogger.d("Traffic baseline initialized/reset for $targetIdentity to $currentBytes")
                        callSessionRepository.setLastDataSubId(currentDataSubId)
                        inMemoryLastDataSubId = currentDataSubId
                        _status.value = TrackingStatus.Active
                        return@withLock
                    }

                    val deltaBytes = currentBytes - lastTotalBytes
                    if (deltaBytes.compareTo(0L) > 0) {
                        val deltaMB = deltaBytes.toFloat() / Constants.BYTES_PER_MB
                        if (deltaMB >= 0.0001f) {
                            simUsageRepository.setLastTotalBytes(targetIdentity, currentBytes)
                            simUsageRepository.incrementTrafficUsed(targetIdentity, deltaMB)
                            AppLogger.d("Added $deltaMB MB to identity $targetIdentity (SIM $targetSim)")
                            callSessionRepository.setLastDataSubId(currentDataSubId)
                            inMemoryLastDataSubId = currentDataSubId
                            _status.value = TrackingStatus.Active
                        }
                    } else {
                        _status.value = TrackingStatus.Active
                    }
                } catch (e: SecurityException) {
                    AppLogger.e("Permission error updating traffic", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
                } catch (e: CancellationException) {
                    throw e
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
                        timeProvider.now(),
                    )
                bucket.rxBytes + bucket.txBytes
            } catch (e: SecurityException) {
                throw e
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: SecurityException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error getting default data SIM", e)
            }

            if (targetSim == -1) {
                targetSim = findSingleActiveSimSlot() ?: -1
            }

            if (targetSim == -1) return null

            // Detect SIM change for baseline reset
            if (inMemoryLastDataSubId == -1) {
                inMemoryLastDataSubId = callSessionRepository.getLastDataSubId().first()
            }

            val savedLastDataSubId = inMemoryLastDataSubId
            if (savedLastDataSubId != -1 && currentDataSubId != -1 && savedLastDataSubId != currentDataSubId) {
                AppLogger.d("Default data SIM changed (from $savedLastDataSubId to $currentDataSubId). Resetting baseline.")
                val identity = simIdentityRepository.getIdentity(targetSim)
                val currentBytes = getCurrentMobileBytes()
                if (currentBytes != -1L) {
                    simUsageRepository.setLastTotalBytes(identity, currentBytes)
                }
                callSessionRepository.setLastDataSubId(currentDataSubId)
                inMemoryLastDataSubId = currentDataSubId
                return null // Skip delta for the swap event
            }

            return targetSim to currentDataSubId
        }

        private suspend fun findSingleActiveSimSlot(): Int? {
            val activeSlots = mutableListOf<Int>()

            // We support up to 2 SIM slots
            for (slot in SimConstants.SUPPORTED_SLOT_INDICES) {
                try {
                    val identity = simIdentityRepository.getIdentity(slot)
                    val operator = simIdentityRepository.getSimOperator(identity).first()

                    if (!SimUtils.isInactiveSim(operator)) {
                        activeSlots.add(slot)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Error checking active status for slot $slot: ${e.message}")
                }
            }

            return if (activeSlots.size == 1) activeSlots.first() else null
        }
    }
