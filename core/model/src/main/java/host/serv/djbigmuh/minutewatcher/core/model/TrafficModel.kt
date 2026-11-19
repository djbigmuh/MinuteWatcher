package host.serv.djbigmuh.minutewatcher.core.model

import host.serv.djbigmuh.minutewatcher.core.model.util.ClampingUtils
import host.serv.djbigmuh.minutewatcher.core.model.util.UnitConversionUtils

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
            val limitGbD = UnitConversionUtils.mbToGb(limitMb.toFloat()).toDouble()
            val safeUsedMb = usedMb.coerceAtLeast(0f)
            val usedGbD = UnitConversionUtils.mbToGb(safeUsedMb).toDouble()
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
                usedMb = safeUsedMb,
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
