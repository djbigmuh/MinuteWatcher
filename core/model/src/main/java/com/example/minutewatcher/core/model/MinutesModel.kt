package com.example.minutewatcher.core.model

data class MinutesModel(
    val used: Int,
    val limit: Int,
    val manualAdjustment: Int,
    val remaining: Int,
) {
    companion object {
        fun calculate(
            used: Int,
            limit: Int,
            manualAdjustment: Int,
        ): MinutesModel {
            val totalLimit = limit + manualAdjustment
            val remaining = if (limit > 0 || manualAdjustment != 0) maxOf(0, totalLimit - used) else 0
            return MinutesModel(used, limit, manualAdjustment, remaining)
        }
    }
}
