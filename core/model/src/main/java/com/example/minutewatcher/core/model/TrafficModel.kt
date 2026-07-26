package com.example.minutewatcher.core.model

data class TrafficModel(
    val usedMb: Float,
    val limitMb: Int,
    val manualAdjustmentGb: Float,
    val remainingGb: Float,
    val limitGb: Float,
    val isLimitSet: Boolean,
) {
    companion object {
        fun calculate(
            usedMb: Float,
            limitMb: Int,
            manualAdjustmentGb: Float,
        ): TrafficModel {
            val limitGb = limitMb / Constants.MB_PER_GB
            val usedGb = usedMb / Constants.MB_PER_GB
            val remainingGb = if (limitMb > 0 || manualAdjustmentGb != 0f) limitGb + manualAdjustmentGb - usedGb else -1f
            return TrafficModel(usedMb, limitMb, manualAdjustmentGb, remainingGb, limitGb, limitMb > 0)
        }
    }
}
