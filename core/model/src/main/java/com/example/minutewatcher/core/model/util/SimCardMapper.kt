package com.example.minutewatcher.core.model.util

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
        val remainingMinutes =
            if (usage.minutesLimit > 0 || usage.manualAdjustment != 0) {
                usage.minutesLimit + usage.manualAdjustment - usage.minutesUsed
            } else {
                0
            }

        val trafficLimitGb = usage.trafficLimitMb / Constants.MB_PER_GB
        val trafficRemainingGb =
            if (usage.trafficLimitMb > 0) {
                trafficLimitGb + usage.trafficAdjustment - (usage.trafficUsed / Constants.MB_PER_GB)
            } else {
                -1f
            }

        return SimCardUiModel(
            slot = slot,
            identity = usage.identity,
            operatorName = SimUtils.getShortOperatorName(context, usage.operator),
            operatorCode = usage.identity, // identity is used for matching
            iconRes = SimUtils.getOperatorIconResource(usage.operator),
            minutesUsed = usage.minutesUsed,
            minutesLimit = usage.minutesLimit,
            manualAdjustment = usage.manualAdjustment,
            minutesRemaining = remainingMinutes,
            trafficUsedMb = usage.trafficUsed,
            trafficLimitMb = usage.trafficLimitMb,
            trafficRemainingGb = trafficRemainingGb,
            isTrafficLimitSet = (usage.trafficLimitMb > 0),
            autoResetEnabled = usage.autoReset,
            autoResetDay = usage.autoResetDay,
        )
    }
}
