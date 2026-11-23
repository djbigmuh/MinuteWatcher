package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TrafficTargetResolver determines the target SIM for traffic tracking.
 *
 * Stale input states (where system state and active SIM list are transiently out of sync)
 * return [TargetResult.StaleInput] and do not alter tracker status or clear retries.
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

            data object NoActiveSims : TargetResult

            /**
             * No default data SIM is selected in the system (e.g., mobile data is off).
             * This is a normal state, not a firmware error.
             */
            data object NoDefaultData : TargetResult

            /**
             * Input data was obtained at different points in time and is mutually inconsistent.
             * This is a transient state that is re-evaluated on the next active SIMs flow emission.
             */
            data object StaleInput : TargetResult

            /**
             * Target SIM resolution failed due to an error unrelated to input staleness.
             */
            data class Failed(
                val message: String?,
            ) : TargetResult
        }

        fun resolve(activeSims: List<ActiveSimInfo>): TargetResult {
            if (activeSims.isEmpty()) {
                AppLogger.d("TrafficTargetResolver: No active SIMs provided")
                return TargetResult.NoActiveSims
            }

            @Suppress("TooGenericExceptionCaught")
            return try {
                val currentDataSubId = queryDefaultDataSubId()
                evaluateTargetResult(activeSims, currentDataSubId)
            } catch (e: Exception) {
                AppLogger.e("TrafficTargetResolver: Unexpected error during resolution", e)
                TargetResult.Failed(e.message)
            }
        }

        private fun queryDefaultDataSubId(): Int {
            @Suppress("TooGenericExceptionCaught")
            return try {
                permissionManager.getDefaultDataSubscriptionId()
            } catch (e: Exception) {
                AppLogger.e("Error getting default data SIM", e)
                -1
            }
        }

        private fun evaluateTargetResult(
            activeSims: List<ActiveSimInfo>,
            currentDataSubId: Int,
        ): TargetResult {
            if (currentDataSubId == -1) {
                val simClaimsDefault = activeSims.any { it.isDefaultData }
                return if (simClaimsDefault) {
                    AppLogger.w(
                        "TrafficTargetResolver: Stale input - " +
                            "system says no default sub, but a SIM claims default status.",
                    )
                    TargetResult.StaleInput
                } else {
                    AppLogger.d("TrafficTargetResolver: No default data SIM selected (likely data off)")
                    TargetResult.NoDefaultData
                }
            }

            val targetSim = activeSims.find { it.subscriptionId == currentDataSubId }?.slotIndex ?: -1

            if (targetSim == -1) {
                AppLogger.w(
                    "TrafficTargetResolver: Stale input - subId $currentDataSubId not found in active SIMs",
                )
                return TargetResult.StaleInput
            }

            if (targetSim !in SimConstants.SUPPORTED_SLOT_INDICES) {
                val message = "simSlotIndex $targetSim out of supported range"
                AppLogger.e("TrafficTargetResolver: $message")
                return TargetResult.Failed(message)
            }

            AppLogger.d("TrafficTargetResolver: Resolved target SIM slot $targetSim (subId $currentDataSubId)")
            return TargetResult.Success(TargetInfo(targetSim, currentDataSubId))
        }
    }
