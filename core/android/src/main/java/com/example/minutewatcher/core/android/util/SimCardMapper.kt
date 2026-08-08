package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
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
        val hasNegativeValues =
            usage.minutesUsed < 0 ||
                usage.minutesLimit < 0 ||
                usage.trafficUsedMb < 0f ||
                usage.trafficLimitMb < 0

        if (hasNegativeValues) {
            AppLogger.w(
                "SimCardMapper: Detected negative usage/limit values for slot $slot: " +
                    "minUsed=${usage.minutesUsed}, minLimit=${usage.minutesLimit}, " +
                    "trafUsed=${usage.trafficUsedMb}, trafLimit=${usage.trafficLimitMb}",
            )
        }

        val safeMinutesUsed = usage.minutesUsed.coerceAtLeast(0)
        val safeMinutesLimit = usage.minutesLimit.coerceAtLeast(0)
        val safeTrafficUsedMb = usage.trafficUsedMb.coerceAtLeast(0f)
        val safeTrafficLimitMb = usage.trafficLimitMb.coerceAtLeast(0)

        val minutesModel =
            MinutesModel.calculate(
                used = safeMinutesUsed,
                limit = safeMinutesLimit,
                manualAdjustment = usage.manualAdjustment,
            )

        val trafficModel =
            TrafficModel.calculate(
                usedMb = safeTrafficUsedMb,
                limitMb = safeTrafficLimitMb,
                manualAdjustmentGb = usage.trafficAdjustmentGb,
            )

        return SimCardUiModel(
            slot = slot,
            identity = usage.identity,
            operatorName = SimUtils.getShortOperatorName(context, usage.operator),
            iconRes = SimUtils.getOperatorIconResource(usage.operator),
            minutesUsed = safeMinutesUsed,
            minutesLimit = safeMinutesLimit,
            manualAdjustment = usage.manualAdjustment,
            minutesRemaining = minutesModel.remaining,
            effectiveMinutesLimit = minutesModel.effectiveLimit,
            isMinutesLimitSet = minutesModel.isLimitSet,
            trafficUsedMb = safeTrafficUsedMb,
            trafficLimitMb = safeTrafficLimitMb,
            trafficRemainingGb = trafficModel.remainingGb,
            effectiveTrafficLimitGb = trafficModel.effectiveLimitGb,
            trafficAdjustmentGb = usage.trafficAdjustmentGb,
            isTrafficLimitSet = trafficModel.isLimitSet,
            autoResetEnabled = usage.autoReset,
            autoResetDay = usage.autoResetDay,
            rolloverEnabled = usage.rolloverEnabled,
        )
    }
}
