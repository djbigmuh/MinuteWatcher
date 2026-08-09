package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel
import java.util.LinkedHashMap

/**
 * Mappers for converting domain models (SimUsage) to UI models (SimCardUiModel).
 *
 * This object maintains a small internal cache [negativeLogged] to prevent log spamming
 * when negative usage values are detected.
 */
object SimCardMapper {
    /**
     * Cache to track which SIM identities have already been logged for negative values in a slot.
     * Prevents [AppLogger.e] from being called repeatedly for the same inaccurate state.
     * The map size is limited to [MAX_LOGGED_SIZE] to prevent memory leaks.
     */
    private val negativeLogged =
        object : LinkedHashMap<Int, String>(MAX_LOGGED_SIZE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > MAX_LOGGED_SIZE
        }
    private const val MAX_LOGGED_SIZE = 20
    private const val LOAD_FACTOR = 0.75f

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
            synchronized(negativeLogged) {
                val lastLoggedIdentity = negativeLogged[slot]
                if (lastLoggedIdentity != usage.identity) {
                    AppLogger.e(
                        "SimCardMapper: Detected negative usage/limit values for slot $slot (id=${usage.identity}): " +
                            "minUsed=${usage.minutesUsed}, minLimit=${usage.minutesLimit}, " +
                            "trafUsed=${usage.trafficUsedMb}, trafLimit=${usage.trafficLimitMb}",
                    )
                    negativeLogged[slot] = usage.identity
                }
            }
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
