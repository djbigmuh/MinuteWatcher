package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants.SimConstants
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

            /**
             * No default data SIM is selected in the system (e.g., mobile data is off).
             * This is a normal state, not a firmware error.
             */
            object NoDefaultData : TargetResult

            object InconsistentFirmware : TargetResult
        }

        @Suppress("ReturnCount")
        fun resolve(activeSims: List<ActiveSimInfo>): TargetResult {
            return try {
                if (activeSims.isEmpty()) {
                    AppLogger.d("TrafficTargetResolver: No active SIMs provided")
                    return TargetResult.NoActiveSims
                }

                val currentDataSubId =
                    try {
                        permissionManager.getDefaultDataSubscriptionId()
                    } catch (e: Exception) {
                        AppLogger.e("Error getting default data SIM", e)
                        -1
                    }

                if (currentDataSubId == -1) {
                    val simClaimsDefault = activeSims.any { it.isDefaultData }
                    return if (simClaimsDefault) {
                        AppLogger.w(
                            "TrafficTargetResolver: Inconsistent state - " +
                                "system says no default sub, but a SIM claims default status.",
                        )
                        TargetResult.InconsistentFirmware
                    } else {
                        AppLogger.d("TrafficTargetResolver: No default data SIM selected (likely data off)")
                        TargetResult.NoDefaultData
                    }
                }

                val targetSim = activeSims.find { it.subscriptionId == currentDataSubId }?.slotIndex ?: -1

                if (targetSim == -1) {
                    AppLogger.w("TrafficTargetResolver: Default data subId $currentDataSubId not found in active SIMs")
                    return TargetResult.InconsistentFirmware
                }

                if (targetSim !in SimConstants.SUPPORTED_SLOT_INDICES) {
                    AppLogger.w("TrafficTargetResolver: simSlotIndex $targetSim out of supported range")
                    return TargetResult.InconsistentFirmware
                }

                AppLogger.d("TrafficTargetResolver: Resolved target SIM to slot $targetSim (subId $currentDataSubId)")
                TargetResult.Success(TargetInfo(targetSim, currentDataSubId))
            } catch (e: Exception) {
                AppLogger.e("TrafficTargetResolver: Unexpected error during resolution", e)
                TargetResult.InconsistentFirmware
            }
        }
    }
