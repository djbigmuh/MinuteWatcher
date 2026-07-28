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
            val totalLimitGb = limitGb + manualAdjustmentGb
            val isLimitSet = limitMb > 0 || manualAdjustmentGb != 0f
            val remainingGb = if (isLimitSet) maxOf(0f, totalLimitGb - usedGb) else -1f
            return TrafficModel(usedMb, limitMb, manualAdjustmentGb, remainingGb, limitGb, isLimitSet)
        }
    }
}
