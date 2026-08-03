package com.example.minutewatcher.core.model

data class MinutesModel(
    val used: Int,
    val limit: Int,
    val manualAdjustment: Int,
    val remaining: Int,
    val effectiveLimit: Int,
    val isLimitSet: Boolean,
) {
    companion object {
        fun calculate(
            used: Int,
            limit: Int,
            manualAdjustment: Int,
        ): MinutesModel {
            val effectiveLimit = maxOf(0, limit + manualAdjustment)
            val isLimitSet = limit > 0 || manualAdjustment != 0
            val remaining = if (isLimitSet) maxOf(0, effectiveLimit - used) else 0
            return MinutesModel(used, limit, manualAdjustment, remaining, effectiveLimit, isLimitSet)
        }
    }
}
