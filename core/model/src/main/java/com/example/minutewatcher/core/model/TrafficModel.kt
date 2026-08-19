package com.example.minutewatcher.core.model

import com.example.minutewatcher.core.model.util.ClampingUtils


data class TrafficModel(
    val usedMb: Float,
    val limitMb: Int,
    val manualAdjustmentGb: Float,
    val remainingGb: Float,
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
            val clampedEffectiveLimitGb = ClampingUtils.clamp(effectiveLimitGbD.toFloat(), 0f, Constants.MAX_TRAFFIC_LIMIT_GB)

            val isLimitSet = limitMb > 0 || manualAdjustmentGb != 0f
            val remainingGbD = if (isLimitSet) maxOf(0.0, clampedEffectiveLimitGb.toDouble() - usedGbD) else -1.0
            val clampedRemainingGb = if (isLimitSet) ClampingUtils.clamp(remainingGbD.toFloat(), 0f, Constants.MAX_TRAFFIC_LIMIT_GB) else -1.0f

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
