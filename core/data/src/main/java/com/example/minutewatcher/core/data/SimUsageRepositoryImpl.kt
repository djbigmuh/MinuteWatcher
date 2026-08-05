package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimUsageRepositoryImpl
    @Inject
    constructor(
        @SimDataStore dataStore: DataStore<Preferences>,
        private val timeProvider: TimeProvider,
    ) : BaseDataStoreRepository(dataStore),
        SimUsageRepository,
        SimUsageAdjustmentRepository,
        SimUsageResetRepository {

        override fun getSimUsage(identity: String): Flow<SimUsage> =
            dataStore.data.map { prefs ->
                SimUsage(
                    identity = identity,
                    slotIndex = prefs[SimSettingsKeys.slotIndexKey(identity)] ?: 0,
                    subscriptionId = prefs[SimSettingsKeys.subscriptionIdKey(identity)],
                    operator =
                        prefs[SimSettingsKeys.customSimOperatorKey(identity)]
                            ?: prefs[SimSettingsKeys.simOperatorKey(identity)]
                            ?: "",
                    minutesUsed = prefs[SimSettingsKeys.minutesUsedKey(identity)] ?: 0,
                    minutesLimit = prefs[SimSettingsKeys.monthlyLimitKey(identity)] ?: 0,
                    trafficUsed = prefs[SimSettingsKeys.trafficUsedKey(identity)] ?: 0f,
                    trafficLimitMb = prefs[SimSettingsKeys.trafficLimitKey(identity)] ?: 0,
                    manualAdjustment = prefs[SimSettingsKeys.manualAdjustmentKey(identity)] ?: 0,
                    trafficAdjustment = prefs[SimSettingsKeys.trafficAdjustmentKey(identity)] ?: 0f,
                    autoReset = prefs[SimSettingsKeys.autoResetKey(identity)] ?: false,
                    autoResetDay = prefs[SimSettingsKeys.autoResetDayKey(identity)] ?: 1,
                    lastResetDate = prefs[SimSettingsKeys.lastResetDateKey(identity)] ?: 0L,
                    rolloverEnabled = prefs[SimSettingsKeys.rolloverEnabledKey(identity)] ?: false,
                )
            }

        override suspend fun setMinutesUsed(
            identity: String,
            value: Int,
        ) {
            dataStore.edit { it[SimSettingsKeys.minutesUsedKey(identity)] = value }
        }

        override suspend fun incrementMinutesUsed(
            identity: String,
            delta: Int,
        ) {
            dataStore.edit { prefs ->
                val current = prefs[SimSettingsKeys.minutesUsedKey(identity)] ?: 0
                prefs[SimSettingsKeys.minutesUsedKey(identity)] = current + delta
            }
        }

        override suspend fun setTrafficUsed(
            identity: String,
            value: Float,
        ) {
            dataStore.edit { it[SimSettingsKeys.trafficUsedKey(identity)] = value }
        }

        override suspend fun incrementTrafficUsed(
            identity: String,
            deltaMb: Float,
        ) {
            dataStore.edit { prefs ->
                val current = prefs[SimSettingsKeys.trafficUsedKey(identity)] ?: 0f
                prefs[SimSettingsKeys.trafficUsedKey(identity)] = current + deltaMb
            }
        }

        override suspend fun setManualAdjustment(
            identity: String,
            value: Int,
        ) {
            dataStore.edit { it[SimSettingsKeys.manualAdjustmentKey(identity)] = value }
        }

        override suspend fun setTrafficAdjustment(
            identity: String,
            value: Float,
        ) {
            dataStore.edit { it[SimSettingsKeys.trafficAdjustmentKey(identity)] = value }
        }

        override suspend fun updateSimStats(
            identity: String,
            minutesUsed: Int,
            trafficUsed: Float,
            manualAdjustment: Int,
        ) {
            dataStore.edit {
                it[SimSettingsKeys.minutesUsedKey(identity)] = minutesUsed
                it[SimSettingsKeys.trafficUsedKey(identity)] = trafficUsed
                it[SimSettingsKeys.manualAdjustmentKey(identity)] = manualAdjustment
            }
        }

        override suspend fun resetSimUsage(identity: String) {
            dataStore.edit { prefs ->
                prefs.applyResetWithRollover(identity, timeProvider.now())
            }
        }

        override suspend fun resetSimUsageManual(identity: String) {
            dataStore.edit { prefs ->
                prefs[SimSettingsKeys.minutesUsedKey(identity)] = 0
                prefs[SimSettingsKeys.trafficUsedKey(identity)] = 0f
                prefs[SimSettingsKeys.manualAdjustmentKey(identity)] = 0
                prefs[SimSettingsKeys.trafficAdjustmentKey(identity)] = 0f
                prefs[SimSettingsKeys.lastResetDateKey(identity)] = timeProvider.now()

                prefs.resetNotificationFlags(identity)
            }
        }

        override suspend fun resetSimUsageIfDue(
            identity: String,
            autoResetDay: Int,
            nowMillis: Long,
        ): Boolean {
            var didReset = false
            dataStore.edit { prefs ->
                val lastResetDate = prefs[SimSettingsKeys.lastResetDateKey(identity)] ?: 0L
                val periodStart = BillingPeriodCalculator.calculate(autoResetDay, nowMillis).startDate
                if (lastResetDate < periodStart) {
                    prefs.applyResetWithRollover(identity, nowMillis)
                    didReset = true
                }
            }
            return didReset
        }

        private fun MutablePreferences.applyResetWithRollover(
            identity: String,
            nowMillis: Long,
        ) {
            val rollover = this[SimSettingsKeys.rolloverEnabledKey(identity)] ?: false
            if (rollover) {
                val minutesLimit = this[SimSettingsKeys.monthlyLimitKey(identity)] ?: 0
                val minutesUsed = this[SimSettingsKeys.minutesUsedKey(identity)] ?: 0
                val manualAdj = this[SimSettingsKeys.manualAdjustmentKey(identity)] ?: 0
                val remainingMinutes =
                    maxOf(
                        0L,
                        (minutesLimit.toLong() + manualAdj.toLong()) - minutesUsed.toLong(),
                    ).toInt()

                val trafficLimitMb = this[SimSettingsKeys.trafficLimitKey(identity)] ?: 0
                val trafficUsedMb = this[SimSettingsKeys.trafficUsedKey(identity)] ?: 0f
                val trafficAdjGb = this[SimSettingsKeys.trafficAdjustmentKey(identity)] ?: 0f
                val remainingMb =
                    maxOf(
                        0f,
                        (trafficLimitMb.toFloat() + trafficAdjGb * Constants.MB_PER_GB) - trafficUsedMb,
                    )
                val remainingTrafficGb = remainingMb / Constants.MB_PER_GB

                this[SimSettingsKeys.manualAdjustmentKey(identity)] = remainingMinutes
                this[SimSettingsKeys.trafficAdjustmentKey(identity)] = remainingTrafficGb
            } else {
                this[SimSettingsKeys.manualAdjustmentKey(identity)] = 0
                this[SimSettingsKeys.trafficAdjustmentKey(identity)] = 0f
            }

            this[SimSettingsKeys.minutesUsedKey(identity)] = 0
            this[SimSettingsKeys.trafficUsedKey(identity)] = 0f
            this[SimSettingsKeys.lastResetDateKey(identity)] = nowMillis

            resetNotificationFlags(identity)
        }

        private fun MutablePreferences.resetNotificationFlags(identity: String) {
            this[NotificationSettingsKeys.notifiedLowMinutesKey(identity)] = false
            this[NotificationSettingsKeys.notifiedLowTrafficKey(identity)] = false
            this[NotificationSettingsKeys.notifiedExhaustedMinutesKey(identity)] = false
            this[NotificationSettingsKeys.notifiedExhaustedTrafficKey(identity)] = false
        }

        override fun getLastResetDate(identity: String): Flow<Long> =
            getData(SimSettingsKeys.lastResetDateKey(identity), 0L)

        override suspend fun setLastResetDate(
            identity: String,
            value: Long,
        ) {
            dataStore.edit { it[SimSettingsKeys.lastResetDateKey(identity)] = value }
        }

        override fun getLastTotalBytes(identity: String): Flow<Long> =
            getData(DynamicSettingsKeys.lastTotalBytesKey(identity), -1L)

        override suspend fun setLastTotalBytes(
            identity: String,
            bytes: Long,
        ) {
            dataStore.edit { it[DynamicSettingsKeys.lastTotalBytesKey(identity)] = bytes }
        }
    }
