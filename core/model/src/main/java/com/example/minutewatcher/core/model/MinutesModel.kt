package com.example.minutewatcher.core.model

import com.example.minutewatcher.core.model.util.ClampingUtils


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
            val rawEffective = limit + manualAdjustment
            val effectiveLimit = ClampingUtils.clamp(rawEffective, 0, Constants.MAX_MINUTES_LIMIT)
            val isLimitSet = limit > 0 || manualAdjustment != 0
            val remaining = if (isLimitSet) ClampingUtils.clamp(effectiveLimit - used, 0, Constants.MAX_MINUTES_LIMIT) else 0
            return MinutesModel(used, limit, manualAdjustment, remaining, effectiveLimit, isLimitSet)
        }
    }
}
