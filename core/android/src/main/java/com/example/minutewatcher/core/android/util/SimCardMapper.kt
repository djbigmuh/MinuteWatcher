package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrafficModel
import com.example.minutewatcher.core.model.util.ClampingUtils
import com.example.minutewatcher.core.resources.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Mappers for converting domain models (SimUsage) to UI models (SimCardUiModel).
 *
 * This object maintains a small internal cache [negativeLogged] to prevent log spamming
 * when negative usage values are detected.
 */
object SimCardMapper {
    /**
     * Cache of SIM identities for which a negative value warning has already been logged.
     * Prevents repeated logging of the same identity in one slot.
     *
     * Uses [ConcurrentHashMap] for thread safety.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val negativeLogged = ConcurrentHashMap<Int, String>()

    /**
     * Clears cache entries for slots that are no longer active.
     *
     * @param activeSlots The set of currently active SIM slot indices.
     */
    fun cleanupSlots(activeSlots: Set<Int>) {
        negativeLogged.keys.removeIf { it !in activeSlots }
    }

    /**
     * Clears all cache entries for testing.
     */
    @VisibleForTesting
    fun resetForTest() {
        negativeLogged.clear()
    }

    fun map(
        context: Context,
        slot: Int,
        usage: SimUsage,
    ): SimCardUiModel {
        if (slot !in Constants.SimConstants.SUPPORTED_SLOT_INDICES) {
            AppLogger.e("SimCardMapper: Attempted to map invalid slot $slot (id=${usage.identity})")
            return createDefaultModel(slot, usage.identity)
        }

        val hasNegativeValues =
            usage.minutesUsed < 0 ||
                usage.minutesLimit < 0 ||
                usage.trafficUsedMb < 0f ||
                usage.trafficLimitMb < 0

        // negativeLogged cache suppresses repeated logging of negative
        // values for the same identity in a slot. On SIM change
        // in the slot (identity changed) the old entry is removed.
        updateNegativeLoggedState(slot, usage, hasNegativeValues)

        val safeMinutesUsed = ClampingUtils.clamp(usage.minutesUsed, 0, Constants.MAX_MINUTES_LIMIT)
        val safeMinutesLimit = ClampingUtils.clamp(usage.minutesLimit, 0, Constants.MAX_MINUTES_LIMIT)

        val maxTrafficMb = Constants.MAX_TRAFFIC_LIMIT_MB
        val safeTrafficUsedMb = ClampingUtils.clamp(usage.trafficUsedMb, 0f, maxTrafficMb.toFloat())
        val safeTrafficLimitMb = ClampingUtils.clamp(usage.trafficLimitMb, 0, maxTrafficMb)

        // manualAdjustment and trafficAdjustmentGb are passed to calculate()
        // without preliminary clamping. Negative values are allowed
        // (adjustment downwards). MinutesModel.calculate and
        // TrafficModel.calculate guarantee effectiveLimit >= 0 via ClampingUtils.clamp.
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
            operatorName = SimUtils.getShortOperatorName(context, usage.operator, slot),
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

    private fun updateNegativeLoggedState(
        slot: Int,
        usage: SimUsage,
        hasNegativeValues: Boolean,
    ) {
        var shouldLog = false
        negativeLogged.compute(slot) { _, currentIdentity ->
            if (hasNegativeValues) {
                if (currentIdentity != usage.identity) {
                    shouldLog = true
                    usage.identity
                } else {
                    currentIdentity
                }
            } else {
                null
            }
        }

        if (shouldLog) {
            AppLogger.e(
                "SimCardMapper: Detected negative usage/limit values for slot $slot (id=${usage.identity}): " +
                    "minUsed=${usage.minutesUsed}, minLimit=${usage.minutesLimit}, " +
                    "trafUsed=${usage.trafficUsedMb}, trafLimit=${usage.trafficLimitMb}",
            )
        }
    }

    private fun createDefaultModel(
        slot: Int,
        identity: String,
    ) = SimCardUiModel(
        slot = slot,
        identity = identity,
        operatorName = "",
        iconRes = R.drawable.ic_unknown,
        minutesUsed = 0,
        minutesLimit = 0,
        manualAdjustment = 0,
        minutesRemaining = 0,
        effectiveMinutesLimit = 0,
        isMinutesLimitSet = false,
        trafficUsedMb = 0f,
        trafficLimitMb = 0,
        trafficRemainingGb = 0f,
        effectiveTrafficLimitGb = 0f,
        trafficAdjustmentGb = 0f,
        isTrafficLimitSet = false,
        autoResetEnabled = false,
        autoResetDay = 1,
        rolloverEnabled = false,
    )
}
