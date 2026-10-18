package host.serv.djbigmuh.minutewatcher.feature.semantic.appfunctions

import androidx.appfunctions.AppFunctionSerializable

/** Usage summary of a single SIM card. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SimUsageSummary(
    /** Physical SIM slot index, zero-based. */
    val slot: Int,
    /** Mobile operator name as shown in the app. */
    val operatorName: String,
    /** True if a monthly minutes limit is configured for this SIM. */
    val minutesLimitSet: Boolean,
    /** Remaining minutes in the current billing period; 0 if no limit is set. */
    val minutesRemaining: Long,
    /** Effective monthly minutes limit; 0 if not set. */
    val minutesLimit: Long,
    /** True if a monthly mobile data limit is configured for this SIM. */
    val dataLimitSet: Boolean,
    /** Remaining mobile data in gigabytes (GB) in the current billing period; 0 if no limit is set. */
    val dataRemainingGb: Double,
    /** Effective monthly mobile data limit in gigabytes (GB); 0 if not set. */
    val dataLimitGb: Double,
    /** End of the current billing period, epoch milliseconds. */
    val periodEndMillis: Long,
)

/** Snapshot of remaining minutes and mobile data for all active SIM cards. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class UsageSummary(
    /** When the usage data was last refreshed, epoch milliseconds. */
    val snapshotTimestampMillis: Long,
    /** Per-SIM usage summaries, ordered by slot index. */
    val sims: List<SimUsageSummary>,
)
