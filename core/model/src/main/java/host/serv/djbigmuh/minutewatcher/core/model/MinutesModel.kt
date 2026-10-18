package host.serv.djbigmuh.minutewatcher.core.model

import host.serv.djbigmuh.minutewatcher.core.model.util.ClampingUtils

/**
 * Model representing minutes usage, limits, and adjustments.
 *
 * @property used Minutes used in current period.
 * @property limit Base minutes limit for current billing period.
 * @property manualAdjustment Manual adjustment of minutes for current period.
 * @property remaining Remaining minutes in current period.
 * @property effectiveLimit Effective minutes limit including manual adjustments.
 * @property isLimitSet Whether a minutes limit is configured.
 */
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
