package com.example.minutewatcher.core.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks mobile traffic usage for the device.
 * Attributes all mobile traffic to the CURRENT DEFAULT DATA SIM.
 */
@Singleton
class TrafficTracker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
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

        private val inMemoryLastDataSubId = AtomicInteger(-1)

        private data class TargetInfo(
            val slot: Int,
            val dataSubId: Int,
            val skipDelta: Boolean,
        )

        suspend fun resetBaseline(identity: String) {
            trafficMutex.withLock {
                try {
                    if (!checkPermissions()) return@withLock

                    val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout() ?: return@withLock
                    val currentBytes = getCurrentMobileBytes(usage.lastResetDate, usage.autoResetDay)
                    if (currentBytes != -1L) {
                        simUsageResetRepository.setLastTotalBytes(identity, currentBytes)
                        AppLogger.d("Traffic baseline reset for $identity to $currentBytes")
                        _status.value = TrackingStatus.Active
                    } else {
                        _status.value = TrackingStatus.Idle
                    }
                } catch (e: SecurityException) {
                    handleSecurityException(e)
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
                    if (!checkPermissions()) return@withLock

                    val target =
                        getTargetSimInfo() ?: run {
                            handleNoTarget()
                            return@withLock
                        }

                    if (target.skipDelta) {
                        _status.value = TrackingStatus.Active
                        return@withLock
                    }

                    processTrafficUpdate(target)
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error updating traffic", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                }
            }
        }

        private fun checkPermissions(): Boolean {
            if (!permissionManager.hasUsageStatsPermission()) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, "Usage Access missing")
                return false
            }
            if (!permissionManager.hasPhoneStatePermission()) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, "Phone State missing")
                return false
            }
            return true
        }

        private fun handleSecurityException(e: SecurityException) {
            AppLogger.e("SecurityException in TrafficTracker", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
        }

        private fun handleNoTarget() {
            _status.value = TrackingStatus.Idle
        }

        private suspend fun processTrafficUpdate(target: TargetInfo) {
            val targetIdentity = simIdentityRepository.getIdentity(target.slot)
            val usage = simUsageRepository.getSimUsage(targetIdentity).firstWithTimeout() ?: return
            val currentBytes = getCurrentMobileBytes(usage.lastResetDate, usage.autoResetDay)

            if (currentBytes == -1L) {
                handleStatsUnavailable(targetIdentity)
                return
            }

            val lastTotalBytes = simUsageResetRepository.getLastTotalBytes(targetIdentity).firstWithTimeout() ?: -1L

            if (lastTotalBytes == -1L || currentBytes < lastTotalBytes) {
                handleFirstRunOrReset(targetIdentity, currentBytes, target.dataSubId)
            } else {
                handleTrafficDelta(targetIdentity, currentBytes, lastTotalBytes, target.slot, target.dataSubId)
            }
            _status.value = TrackingStatus.Active
        }

        private fun handleStatsUnavailable(targetIdentity: String) {
            if (networkStatsManager == null) {
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "NetworkStatsManager unavailable")
            } else {
                AppLogger.d("Traffic stats unavailable for $targetIdentity")
                _status.value = TrackingStatus.Idle
            }
        }

        private suspend fun handleFirstRunOrReset(
            identity: String,
            currentBytes: Long,
            dataSubId: Int,
        ) {
            simUsageResetRepository.setLastTotalBytes(identity, currentBytes)
            AppLogger.d("Traffic baseline initialized/reset for $identity to $currentBytes")
            updateLastDataSubId(dataSubId)
        }

        private suspend fun handleTrafficDelta(
            identity: String,
            currentBytes: Long,
            lastTotalBytes: Long,
            slot: Int,
            dataSubId: Int,
        ) {
            val deltaBytes = currentBytes - lastTotalBytes
            if (deltaBytes > 0L) {
                val deltaMB = deltaBytes.toFloat() / Constants.BYTES_PER_MB
                if (deltaMB >= Constants.Traffic.MIN_DELTA_MB) {
                    simUsageResetRepository.setLastTotalBytes(identity, currentBytes)
                    simUsageRepository.incrementTrafficUsed(identity, deltaMB)
                    AppLogger.d("Added $deltaMB MB to identity $identity (SIM $slot)")
                    updateLastDataSubId(dataSubId)
                }
            }
        }

        private suspend fun updateLastDataSubId(dataSubId: Int) {
            if (dataSubId != -1) {
                callSessionRepository.setLastDataSubId(dataSubId)
                inMemoryLastDataSubId.set(dataSubId)
            }
        }

        private fun getCurrentMobileBytes(
            startTime: Long,
            autoResetDay: Int,
        ): Long {
            if (networkStatsManager == null) return -1L

            val now = timeProvider.now()
            val safeStart =
                if (startTime > 0) {
                    startTime
                } else {
                    BillingPeriodCalculator.calculate(autoResetDay, now).startDate
                }

            return try {
                // NOTE: subscriberId is null, which means querySummaryForDevice returns aggregate
                // mobile traffic for ALL SIM cards. Per-SIM attribution is currently implemented by
                // assigning all mobile traffic to the active Default Data SIM.
                // On dual-SIM devices, traffic from SIM 2 will be attributed to SIM 1 if SIM 1 is default.
                val bucket =
                    networkStatsManager.querySummaryForDevice(
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                        null,
                        safeStart,
                        now,
                    )
                bucket.rxBytes + bucket.txBytes
            } catch (e: Exception) {
                AppLogger.e("Error querying NetworkStatsManager", e)
                throw e
            }
        }

        private suspend fun getTargetSimInfo(): TargetInfo? {
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
                val fallback = findFallbackActiveSimSlot()
                if (fallback != null) {
                    targetSim = fallback.first
                    currentDataSubId = fallback.second
                    AppLogger.w("No default data SIM identified for traffic tracking. Using fallback heuristic: attributing all traffic to Slot $targetSim.")
                }
            }

            if (targetSim == -1) return null

            if (inMemoryLastDataSubId.get() == -1) {
                val savedId = callSessionRepository.getLastDataSubId().firstWithTimeout() ?: -1
                inMemoryLastDataSubId.compareAndSet(-1, savedId)
            }

            val savedLastDataSubId = inMemoryLastDataSubId.get()
            if (savedLastDataSubId != -1 && currentDataSubId != -1 && savedLastDataSubId != currentDataSubId) {
                handleSimChange(targetSim, currentDataSubId, savedLastDataSubId)
                return TargetInfo(targetSim, currentDataSubId, skipDelta = true)
            }

            return TargetInfo(targetSim, currentDataSubId, skipDelta = false)
        }

        private suspend fun handleSimChange(
            targetSim: Int,
            currentDataSubId: Int,
            savedLastDataSubId: Int,
        ) {
            AppLogger.d("Default data SIM changed from $savedLastDataSubId to $currentDataSubId. Resetting baseline.")
            val identity = simIdentityRepository.getIdentity(targetSim)
            val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout() ?: return
            val currentBytes = getCurrentMobileBytes(usage.lastResetDate, usage.autoResetDay)
            if (currentBytes != -1L) {
                simUsageResetRepository.setLastTotalBytes(identity, currentBytes)
            }
            callSessionRepository.setLastDataSubId(currentDataSubId)
            inMemoryLastDataSubId.set(currentDataSubId)
        }

        private suspend fun findFallbackActiveSimSlot(): Pair<Int, Int>? {
            val activeSims = simIdentityRepository.observeActiveSims().firstWithTimeout() ?: emptyList()
            val defaultDataSim = activeSims.find { it.isDefaultData }

            if (defaultDataSim != null) {
                return defaultDataSim.slotIndex to defaultDataSim.subscriptionId
            }

            val activeSubs = permissionManager.getActiveSubscriptions()
            if (activeSubs.isEmpty()) return null

            // Fallback to the first active subscription if no explicit default data SIM is identified
            return activeSubs.first().simSlotIndex to activeSubs.first().subscriptionId
        }
    }
