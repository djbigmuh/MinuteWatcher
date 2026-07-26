package com.example.minutewatcher.core.model

data class MinutesModel(
    val used: Int,
    val limit: Int,
    val manualAdjustment: Int,
    val remaining: Int
) {
    companion object {
        fun calculate(used: Int, limit: Int, manualAdjustment: Int): MinutesModel {
            val remaining = if (limit > 0 || manualAdjustment != 0) limit + manualAdjustment - used else 0
            return MinutesModel(used, limit, manualAdjustment, remaining)
        }
    }
}
