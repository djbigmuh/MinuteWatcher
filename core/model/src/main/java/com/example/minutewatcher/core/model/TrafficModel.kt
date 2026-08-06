package com.example.minutewatcher.core.model

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

            val effectiveLimitGbD = maxOf(0.0, limitGbD + manualAdjGbD)
            val isLimitSet = limitMb > 0 || manualAdjustmentGb != 0f
            val remainingGbD = if (isLimitSet) maxOf(0.0, effectiveLimitGbD - usedGbD) else -1.0

            return TrafficModel(
                usedMb = usedMb,
                limitMb = limitMb,
                manualAdjustmentGb = manualAdjustmentGb,
                remainingGb = remainingGbD.toFloat(),
                limitGb = limitGbD.toFloat(),
                effectiveLimitGb = effectiveLimitGbD.toFloat(),
                isLimitSet = isLimitSet,
            )
        }
    }
}
