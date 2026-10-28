package host.serv.djbigmuh.minutewatcher.core.model

import androidx.compose.runtime.Immutable

/**
 * SIM card model for UI.
 * Contains all data necessary to display a SIM card in the UI.
 *
 * @property slot SIM slot index.
 * @property identity Stable SIM identity.
 * @property operatorName Operator name.
 * @property iconRes Operator icon resource.
 * @property minutesUsed Minutes used.
 * @property minutesLimit Minutes limit.
 * @property manualAdjustment Manual minutes adjustment.
 * @property minutesRemaining Minutes remaining.
 * @property effectiveMinutesLimit Effective minutes limit.
 * @property isMinutesLimitSet Whether minutes limit is set.
 * @property trafficUsedMb Traffic used (MB).
 * @property trafficLimitMb Traffic limit (MB).
 * @property trafficRemainingGb Traffic remaining (GB), or null if limit is not set.
 * @property effectiveTrafficLimitGb Effective traffic limit (GB).
 * @property trafficAdjustmentGb Traffic adjustment (GB).
 * @property isTrafficLimitSet Whether traffic limit is set.
 * @property autoResetEnabled Whether auto-reset is enabled.
 * @property autoResetDay Auto-reset day of month.
 * @property rolloverEnabled Whether rollover is enabled.
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
    val trafficRemainingGb: Float?,
    val effectiveTrafficLimitGb: Float,
    val trafficAdjustmentGb: Float,
    val isTrafficLimitSet: Boolean,
    val autoResetEnabled: Boolean,
    val autoResetDay: Int,
    val rolloverEnabled: Boolean,
)
