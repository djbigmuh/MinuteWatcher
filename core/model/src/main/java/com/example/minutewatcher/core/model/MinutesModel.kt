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
            val maxLimitLong = Constants.MAX_MINUTES_LIMIT.toLong()
            val rawEffectiveL = limit.toLong() + manualAdjustment.toLong()
            val effectiveLimitL = ClampingUtils.clamp(rawEffectiveL, 0L, maxLimitLong)
            val effectiveLimit = effectiveLimitL.toInt()

            val isLimitSet = limit > 0 || manualAdjustment != 0
            val remainingL =
                if (isLimitSet) {
                    ClampingUtils.clamp(
                        effectiveLimitL - used.toLong(),
                        0L,
                        maxLimitLong,
                    )
                } else {
                    0L
                }
            val remaining = remainingL.toInt()

            return MinutesModel(used, limit, manualAdjustment, remaining, effectiveLimit, isLimitSet)
        }
    }
}
