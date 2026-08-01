package com.example.minutewatcher.core.model

import androidx.compose.runtime.Immutable

/**
 * Единая модель состояния SIM-карты для UI (Этап 3, п. 11 и Этап 5, п. 21).
 */
@Immutable
data class SimCardUiModel(
    val slot: Int,
    val identity: String,
    val operatorName: String,
    val iconRes: Int,
    val minutesUsed: Int,
    val minutesLimit: Int,
    val manualAdjustment: Int,
    val minutesRemaining: Int,
    val effectiveMinutesLimit: Int,
    val isMinutesLimitSet: Boolean,
    val trafficUsedMb: Float,
    val trafficLimitMb: Int,
    val trafficRemainingGb: Float,
    val effectiveTrafficLimitGb: Float,
    val trafficAdjustmentGb: Float,
    val isTrafficLimitSet: Boolean,
    val autoResetEnabled: Boolean,
    val autoResetDay: Int,
    val rolloverEnabled: Boolean,
)
