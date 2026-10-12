package com.example.minutewatcher.core.model

import com.example.minutewatcher.core.model.util.ClampingUtils

/**
 * Traffic model representing data usage, limits, and adjustments.
 *
 * @property usedMb Traffic used in MB.
 * @property limitMb Traffic limit in MB.
 * @property manualAdjustmentGb Manual traffic adjustment in GB.
 * @property remainingGb Remaining traffic in GB, or null if traffic limit is not set.
 * @property limitGb Traffic limit in GB.
 * @property effectiveLimitGb Effective traffic limit in GB.
 * @property isLimitSet Whether traffic limit is set.
 */
data class TrafficModel(
    val usedMb: Float,
    val limitMb: Int,
    val manualAdjustmentGb: Float,
    val remainingGb: Float?,
    val limitGb: Float,
    val effectiveLimitGb: Float,
    val isLimitSet: Boolean,
) {
    companion object {
        fun calculate(
            usedMb: Float,
            limitMb: Int,
            manualAdjustmentGb: Float,
        ): TrafficModel {
            val mbPerGb = Constants.MB_PER_GB.toDouble()
            val limitGbD = limitMb.toDouble() / mbPerGb
            val usedGbD = usedMb.toDouble() / mbPerGb
            val manualAdjGbD = manualAdjustmentGb.toDouble()

            val effectiveLimitGbD = (limitGbD + manualAdjGbD)
            val clampedEffectiveLimitGb =
                ClampingUtils.clamp(
                    effectiveLimitGbD.toFloat(),
                    0f,
                    Constants.MAX_TRAFFIC_LIMIT_GB,
                )

            val isLimitSet = limitMb > 0 || manualAdjustmentGb != 0f
            val remainingGbD = if (isLimitSet) maxOf(0.0, clampedEffectiveLimitGb.toDouble() - usedGbD) else 0.0
            val clampedRemainingGb =
                if (isLimitSet) {
                    ClampingUtils.clamp(
                        remainingGbD.toFloat(),
                        0f,
                        Constants.MAX_TRAFFIC_LIMIT_GB,
                    )
                } else {
                    null
                }

            return TrafficModel(
                usedMb = usedMb,
                limitMb = limitMb,
                manualAdjustmentGb = manualAdjustmentGb,
                remainingGb = clampedRemainingGb,
                limitGb = limitGbD.toFloat(),
                effectiveLimitGb = clampedEffectiveLimitGb,
                isLimitSet = isLimitSet,
            )
        }
    }
}
