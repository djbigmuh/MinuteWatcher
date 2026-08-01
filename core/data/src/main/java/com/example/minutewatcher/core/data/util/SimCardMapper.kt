package com.example.minutewatcher.core.data.util

import android.content.Context
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel

object SimCardMapper {
    fun map(
        context: Context,
        slot: Int,
        usage: SimUsage,
    ): SimCardUiModel {
        val minutesModel =
            MinutesModel.calculate(
                used = usage.minutesUsed,
                limit = usage.minutesLimit,
                manualAdjustment = usage.manualAdjustment,
            )

        val trafficModel =
            TrafficModel.calculate(
                usedMb = usage.trafficUsed,
                limitMb = usage.trafficLimitMb,
                manualAdjustmentGb = usage.trafficAdjustment,
            )

        return SimCardUiModel(
            slot = slot,
            identity = usage.identity,
            operatorName = SimUtils.getShortOperatorName(context, usage.operator),
            iconRes = SimUtils.getOperatorIconResource(usage.operator),
            minutesUsed = usage.minutesUsed,
            minutesLimit = usage.minutesLimit,
            manualAdjustment = usage.manualAdjustment,
            minutesRemaining = minutesModel.remaining,
            effectiveMinutesLimit = minutesModel.effectiveLimit,
            isMinutesLimitSet = (usage.minutesLimit + usage.manualAdjustment) > 0,
            trafficUsedMb = usage.trafficUsed,
            trafficLimitMb = usage.trafficLimitMb,
            trafficRemainingGb = trafficModel.remainingGb,
            effectiveTrafficLimitGb = trafficModel.effectiveLimitGb,
            trafficAdjustmentGb = usage.trafficAdjustment,
            isTrafficLimitSet = trafficModel.isLimitSet,
            autoResetEnabled = usage.autoReset,
            autoResetDay = usage.autoResetDay,
            rolloverEnabled = usage.rolloverEnabled,
        )
    }
}
