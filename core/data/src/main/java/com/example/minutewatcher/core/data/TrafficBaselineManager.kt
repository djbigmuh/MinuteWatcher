package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages traffic baseline (reset, retry, probe).
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class TrafficBaselineManager
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val simUsageResetRepository: SimUsageResetRepository,
        private val permissionManager: PermissionManager,
        private val timeProvider: TimeProvider,
        private val networkStatsProvider: NetworkStatsProvider,
    ) {
        val baselineNeedsRetry = ConcurrentHashMap<String, Int>()

        fun getCurrentMobileBytes(startTime: Long): StatsResult {
            val now = timeProvider.now()
            val effectiveStart = if (startTime == 0L) now else startTime
            return networkStatsProvider.queryMobileBytes(effectiveStart, now)
        }

        suspend fun resetBaseline(
            identity: String,
            status: MutableStateFlow<TrackingStatus>,
            onSuccess: suspend () -> Unit,
        ) {
            try {
                val usage = simUsageRepository.getSimUsage(identity).firstWithTimeout() ?: return
                val statsResult = getCurrentMobileBytes(usage.lastResetDate)

                if (statsResult is StatsResult.Success) {
                    val currentBytes = statsResult.bytes
                    simUsageResetRepository.setLastTotalBytes(identity, currentBytes)
                    AppLogger.d("Traffic baseline reset for $identity to $currentBytes")

                    syncLastDataSubIdIfNeeded(identity, onSuccess)
                    baselineNeedsRetry.remove(identity)
                    status.value = TrackingStatus.Active
                } else {
                    val message =
                        if (statsResult is StatsResult.Error) {
                            AppLogger.e("Traffic stats baseline query failed", statsResult.exception)
                            statsResult.exception.message
                        } else {
                            "Stats unavailable"
                        }
                    status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
                }
            } catch (e: SecurityException) {
                AppLogger.e("SecurityException in TrafficTracker", e)
                status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Error in resetBaseline", e)
                status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        private suspend fun syncLastDataSubIdIfNeeded(
            identity: String,
            onSync: suspend () -> Unit,
        ) {
            val currentDataSubId =
                try {
                    permissionManager.getDefaultDataSubscriptionId()
                } catch (ignored: Exception) {
                    -1
                }
            if (currentDataSubId != -1) {
                val info = permissionManager.getActiveSubscriptions().find { it.subscriptionId == currentDataSubId }
                if (info != null) {
                    val currentIdentity = simIdentityRepository.getIdentity(info.simSlotIndex)
                    if (currentIdentity == identity) {
                        onSync()
                    }
                }
            }
        }

        @Suppress("ReturnCount")
        suspend fun handleBaselineRetry(
            target: TargetInfo,
            targetIdentity: String,
            statsResult: StatsResult,
            status: MutableStateFlow<TrackingStatus>,
            onSuccess: suspend (Int) -> Unit,
        ): Boolean {
            if (statsResult is StatsResult.Success) {
                val currentBytes = statsResult.bytes
                if (baselineNeedsRetry.remove(targetIdentity) != null) {
                    simUsageResetRepository.setLastTotalBytes(targetIdentity, currentBytes)
                    AppLogger.d("Traffic baseline reset on retry for $targetIdentity to $currentBytes")
                    onSuccess(target.dataSubId)
                    status.value = TrackingStatus.Active
                    return true
                }
            } else if (baselineNeedsRetry.getOrDefault(targetIdentity, 0) > 0) {
                AppLogger.w("Stats still unavailable for $targetIdentity, will retry baseline reset later")
                status.value =
                    TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Stats unavailable, retry pending")
                return true
            }
            return false
        }

        fun cleanupRetries(activeIdentities: Set<String>) {
            baselineNeedsRetry.entries.removeIf { it.key !in activeIdentities }
        }

        companion object {
            const val MAX_BASELINE_RETRIES = 3
        }
    }
