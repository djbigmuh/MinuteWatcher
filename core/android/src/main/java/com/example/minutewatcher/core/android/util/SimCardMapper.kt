package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimUsage

object SimCardMapper {
    fun map(
        context: Context,
        slot: Int,
        usage: SimUsage,
    ): SimCardUiModel {
        val effectiveMinutesLimit = usage.minutesLimit + usage.manualAdjustment
        val isMinutesLimitSet = usage.minutesLimit > 0 || usage.manualAdjustment != 0
        val remainingMinutes =
            if (isMinutesLimitSet) {
                maxOf(0, effectiveMinutesLimit - usage.minutesUsed)
            } else {
                0
            }

        val baseTrafficLimitGb = usage.trafficLimitMb / Constants.MB_PER_GB
        val effectiveTrafficLimitGb = baseTrafficLimitGb + usage.trafficAdjustment
        val isTrafficLimitSet = usage.trafficLimitMb > 0 || usage.trafficAdjustment != 0f
        val trafficRemainingGb =
            if (isTrafficLimitSet) {
                maxOf(0f, effectiveTrafficLimitGb - (usage.trafficUsed / Constants.MB_PER_GB))
            } else {
                -1f
            }

        return SimCardUiModel(
            slot = slot,
            identity = usage.identity,
            operatorName = SimUtils.getShortOperatorName(context, usage.operator),
            iconRes = SimUtils.getOperatorIconResource(usage.operator),
            minutesUsed = usage.minutesUsed,
            minutesLimit = usage.minutesLimit,
            manualAdjustment = usage.manualAdjustment,
            minutesRemaining = remainingMinutes,
            effectiveMinutesLimit = effectiveMinutesLimit,
            isMinutesLimitSet = isMinutesLimitSet,
            trafficUsedMb = usage.trafficUsed,
            trafficLimitMb = usage.trafficLimitMb,
            trafficRemainingGb = trafficRemainingGb,
            effectiveTrafficLimitGb = effectiveTrafficLimitGb,
            trafficAdjustmentGb = usage.trafficAdjustment,
            isTrafficLimitSet = isTrafficLimitSet,
            autoResetEnabled = usage.autoReset,
            autoResetDay = usage.autoResetDay,
            rolloverEnabled = usage.rolloverEnabled,
        )
    }
}

