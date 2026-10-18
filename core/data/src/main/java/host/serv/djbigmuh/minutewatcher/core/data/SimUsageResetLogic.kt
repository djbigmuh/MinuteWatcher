package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.preferences.core.MutablePreferences
import host.serv.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logic for resetting SIM usage with rollover support.
 * Operates on [MutablePreferences].
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class SimUsageResetLogic
@Inject
constructor() {
    /**
     * Applies billing period reset for the given SIM identity.
     *
     * Without rollover, adjustments (`manualAdjustment` and `trafficAdjustmentGb`) are reset to 0
     * so the new period starts strictly from base limits.
     * With rollover enabled, the remaining balance (`limit + adjustment - used`) is carried over
     * and recorded as the new period's adjustment.
     * See [host.serv.djbigmuh.minutewatcher.core.model.SimUsage.manualAdjustment].
     */
    fun applyResetWithRollover(
        prefs: MutablePreferences,
        identity: String,
        nowMillis: Long,
    ) {
        val rollover = prefs[DataStoreKeys.rolloverEnabledKey(identity)] ?: false
        if (rollover) {
            val minutesLimit = prefs[DataStoreKeys.monthlyLimitKey(identity)] ?: 0
            val minutesUsed = prefs[DataStoreKeys.minutesUsedKey(identity)] ?: 0
            val manualAdj = prefs[DataStoreKeys.manualAdjustmentKey(identity)] ?: 0
            val remainingMinutes =
                maxOf(
                    0L,
                    (minutesLimit.toLong() + manualAdj.toLong()) - minutesUsed.toLong(),
                ).toInt()

            val trafficLimitMb = prefs[DataStoreKeys.trafficLimitKey(identity)] ?: 0
            val trafficUsedMb = (prefs[DataStoreKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
            val trafficAdjGb = (prefs[DataStoreKeys.trafficAdjustmentKey(identity)] ?: 0f).toDouble()
            val remainingMb =
                maxOf(
                    0.0,
                    ((trafficLimitMb.toDouble() + (trafficAdjGb * Constants.MB_PER_GB)) - trafficUsedMb),
                )
            val remainingTrafficGb = (remainingMb / Constants.MB_PER_GB).toFloat()

            prefs[DataStoreKeys.manualAdjustmentKey(identity)] = remainingMinutes
            prefs[DataStoreKeys.trafficAdjustmentKey(identity)] = remainingTrafficGb
        } else {
            prefs[DataStoreKeys.manualAdjustmentKey(identity)] = 0
            prefs[DataStoreKeys.trafficAdjustmentKey(identity)] = 0f
        }

        prefs[DataStoreKeys.minutesUsedKey(identity)] = 0
        prefs[DataStoreKeys.trafficUsedKey(identity)] = 0f
        prefs[DataStoreKeys.lastResetDateKey(identity)] = nowMillis

        resetNotificationFlags(prefs, identity)
    }

    fun resetNotificationFlags(
        prefs: MutablePreferences,
        identity: String,
    ) {
        prefs[DataStoreKeys.notifiedLowMinutesKey(identity)] = false
        prefs[DataStoreKeys.notifiedLowTrafficKey(identity)] = false
        prefs[DataStoreKeys.notifiedExhaustedMinutesKey(identity)] = false
        prefs[DataStoreKeys.notifiedExhaustedTrafficKey(identity)] = false
        prefs[DataStoreKeys.notifiedCriticalMinutesKey(identity)] = false
        prefs[DataStoreKeys.notifiedCriticalTrafficKey(identity)] = false
    }
}
