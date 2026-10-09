package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.SimUsage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mapper for [SimUsage] and [Preferences].
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class SimUsageMapper
    @Inject
    constructor() {
        fun mapToSimUsage(
            identity: String,
            prefs: Preferences,
        ): SimUsage =
            SimUsage(
                identity = identity,
                slotIndex = prefs[DataStoreKeys.slotIndexKey(identity)] ?: -1,
                subscriptionId = prefs[DataStoreKeys.subscriptionIdKey(identity)],
                operator =
                    prefs[DataStoreKeys.simOperatorKey(identity)].orEmpty(),
                minutesUsed = prefs[DataStoreKeys.minutesUsedKey(identity)] ?: 0,
                minutesLimit = prefs[DataStoreKeys.monthlyLimitKey(identity)] ?: 0,
                trafficUsedMb = prefs[DataStoreKeys.trafficUsedKey(identity)] ?: 0f,
                trafficLimitMb = prefs[DataStoreKeys.trafficLimitKey(identity)] ?: 0,
                manualAdjustment = prefs[DataStoreKeys.manualAdjustmentKey(identity)] ?: 0,
                trafficAdjustmentGb = prefs[DataStoreKeys.trafficAdjustmentKey(identity)] ?: 0f,
                autoReset = prefs[DataStoreKeys.autoResetKey(identity)] ?: false,
                autoResetDay = prefs[DataStoreKeys.autoResetDayKey(identity)] ?: 1,
                lastResetDate = prefs[DataStoreKeys.lastResetDateKey(identity)] ?: 0L,
                rolloverEnabled = prefs[DataStoreKeys.rolloverEnabledKey(identity)] ?: false,
            )
    }
