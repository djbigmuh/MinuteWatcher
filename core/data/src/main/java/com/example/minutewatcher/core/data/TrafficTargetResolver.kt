package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.model.ActiveSimInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TrafficTargetResolver determines the target SIM for traffic tracking.
 */
@Singleton
class TrafficTargetResolver
    @Inject
    constructor(
        private val permissionManager: PermissionManager,
    ) {
        sealed interface TargetResult {
            data class Success(
                val target: TargetInfo,
            ) : TargetResult

            object NoActiveSims : TargetResult

            object InconsistentFirmware : TargetResult
        }

        fun resolve(activeSims: List<ActiveSimInfo>): TargetResult {
            if (activeSims.isEmpty()) return TargetResult.NoActiveSims
            val (targetSim, currentDataSubId) = resolveTargetSim(activeSims) ?: return TargetResult.InconsistentFirmware
            return TargetResult.Success(TargetInfo(targetSim, currentDataSubId))
        }

        private fun resolveTargetSim(activeSims: List<ActiveSimInfo>): Pair<Int, Int>? {
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
                activeSims.find { it.isDefaultData }?.let {
                    targetSim = it.slotIndex
                    currentDataSubId = it.subscriptionId
                }
            }
            return if (targetSim != -1) Pair(targetSim, currentDataSubId) else null
        }
    }
