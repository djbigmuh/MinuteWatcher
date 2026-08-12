package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val resetMutex = Mutex()

        override fun getSimUsage(identity: String): Flow<SimUsage> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getSimUsage called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(createEmptyUsage(identity))
            }
            return dataStore.data.map { prefs -> mapToSimUsage(identity, prefs) }
        }

        private fun createEmptyUsage(identity: String) =
            SimUsage(
                identity = identity,
                slotIndex = -1,
                subscriptionId = null,
                operator = "",
                minutesUsed = 0,
                minutesLimit = 0,
                trafficUsedMb = 0f,
                trafficLimitMb = 0,
                manualAdjustment = 0,
                trafficAdjustmentGb = 0f,
                autoReset = false,
                autoResetDay = 1,
                lastResetDate = 0L,
                rolloverEnabled = false,
            )

        private fun mapToSimUsage(
            identity: String,
            prefs: Preferences,
        ) = SimUsage(
            identity = identity,
            slotIndex = prefs[SimSettingsKeys.slotIndexKey(identity)] ?: 0,
            subscriptionId = prefs[SimSettingsKeys.subscriptionIdKey(identity)],
            operator =
                prefs[SimSettingsKeys.customSimOperatorKey(identity)]
                    ?: prefs[SimSettingsKeys.simOperatorKey(identity)]
                    ?: "",
            minutesUsed = prefs[SimSettingsKeys.minutesUsedKey(identity)] ?: 0,
            minutesLimit = prefs[SimSettingsKeys.monthlyLimitKey(identity)] ?: 0,
            trafficUsedMb = prefs[SimSettingsKeys.trafficUsedKey(identity)] ?: 0f,
            trafficLimitMb = prefs[SimSettingsKeys.trafficLimitKey(identity)] ?: 0,
            manualAdjustment = prefs[SimSettingsKeys.manualAdjustmentKey(identity)] ?: 0,
            trafficAdjustmentGb = prefs[SimSettingsKeys.trafficAdjustmentKey(identity)] ?: 0f,
            autoReset = prefs[SimSettingsKeys.autoResetKey(identity)] ?: false,
            autoResetDay = prefs[SimSettingsKeys.autoResetDayKey(identity)] ?: 1,
            lastResetDate = prefs[SimSettingsKeys.lastResetDateKey(identity)] ?: 0L,
            rolloverEnabled = prefs[SimSettingsKeys.rolloverEnabledKey(identity)] ?: false,
        )

        override suspend fun setMinutesUsed(
            identity: String,
            value: Int,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setMinutesUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[SimSettingsKeys.minutesUsedKey(identity)] = value }
        }

        override suspend fun incrementMinutesUsed(
            identity: String,
            delta: Int,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("incrementMinutesUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                val current = prefs[SimSettingsKeys.minutesUsedKey(identity)] ?: 0
                prefs[SimSettingsKeys.minutesUsedKey(identity)] = current + delta
            }
        }

        override suspend fun setTrafficUsed(
            identity: String,
            valueMb: Float,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setTrafficUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[SimSettingsKeys.trafficUsedKey(identity)] = valueMb }
        }

        override suspend fun incrementTrafficUsed(
            identity: String,
            deltaMb: Float,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("incrementTrafficUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                val current = (prefs[SimSettingsKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                prefs[SimSettingsKeys.trafficUsedKey(identity)] = (current + deltaMb.toDouble()).toFloat()
            }
        }

        override suspend fun setManualAdjustment(
            identity: String,
            value: Int,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setManualAdjustment called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[SimSettingsKeys.manualAdjustmentKey(identity)] = value }
        }

        override suspend fun setTrafficAdjustment(
            identity: String,
            valueGb: Float,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setTrafficAdjustment called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[SimSettingsKeys.trafficAdjustmentKey(identity)] = valueGb }
        }

        override suspend fun updateSimStats(
            identity: String,
            minutesUsed: Int,
            trafficUsedMb: Float,
            manualAdjustment: Int,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("updateSimStats called with invalid identity: $identity")
                return
            }
            dataStore.edit {
                it[SimSettingsKeys.minutesUsedKey(identity)] = minutesUsed
                it[SimSettingsKeys.trafficUsedKey(identity)] = trafficUsedMb
                it[SimSettingsKeys.manualAdjustmentKey(identity)] = manualAdjustment
            }
        }

        override suspend fun resetSimUsage(identity: String) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("resetSimUsage called with invalid identity: $identity")
                return
            }
            resetMutex.withLock {
                dataStore.edit { prefs ->
                    prefs.applyResetWithRollover(identity, timeProvider.now())
                }
            }
        }

        override suspend fun resetSimUsageManual(identity: String) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("resetSimUsageManual called with invalid identity: $identity")
                return
            }
            resetMutex.withLock {
                dataStore.edit { prefs ->
                    prefs[SimSettingsKeys.minutesUsedKey(identity)] = 0
                    prefs[SimSettingsKeys.trafficUsedKey(identity)] = 0f
                    prefs[SimSettingsKeys.manualAdjustmentKey(identity)] = 0
                    prefs[SimSettingsKeys.trafficAdjustmentKey(identity)] = 0f
                    prefs[SimSettingsKeys.lastResetDateKey(identity)] = timeProvider.now()

                    prefs.resetNotificationFlags(identity)
                }
            }
        }

        override suspend fun resetSimUsageIfDue(
            identity: String,
            autoResetDay: Int,
            nowMillis: Long,
        ): Boolean {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("resetSimUsageIfDue called with invalid identity: $identity")
                return false
            }
            return resetMutex.withLock {
                val periodStart = BillingPeriodCalculator.calculate(autoResetDay, nowMillis).startDate

                var actuallyReset = false
                dataStore.edit { prefs ->
                    val innerLastResetDate =
                        prefs[SimSettingsKeys.lastResetDateKey(identity)]
                            ?: Constants.Data.EMPTY_TIMESTAMP
                    if (innerLastResetDate < periodStart) {
                        prefs.applyResetWithRollover(identity, nowMillis)
                        actuallyReset = true
                    }
                }
                actuallyReset
            }
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
                val trafficUsedMb = (this[SimSettingsKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                val trafficAdjGb = (this[SimSettingsKeys.trafficAdjustmentKey(identity)] ?: 0f).toDouble()
                val remainingMb =
                    maxOf(
                        0.0,
                        (trafficLimitMb.toDouble() + trafficAdjGb * Constants.MB_PER_GB) - trafficUsedMb,
                    )
                val remainingTrafficGb = (remainingMb / Constants.MB_PER_GB).toFloat()

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

        override fun getLastResetDate(identity: String): Flow<Long> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getLastResetDate called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(0L)
            }
            return getData(SimSettingsKeys.lastResetDateKey(identity), 0L)
        }

        override suspend fun setLastResetDate(
            identity: String,
            value: Long,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setLastResetDate called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[SimSettingsKeys.lastResetDateKey(identity)] = value }
        }

        override fun getLastTotalBytes(identity: String): Flow<Long> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getLastTotalBytes called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(-1L)
            }
            return getData(DynamicSettingsKeys.lastTotalBytesKey(identity), -1L)
        }

        override suspend fun setLastTotalBytes(
            identity: String,
            bytes: Long,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setLastTotalBytes called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DynamicSettingsKeys.lastTotalBytesKey(identity)] = bytes }
        }

        override suspend fun incrementTrafficAndSetBaseline(
            identity: String,
            deltaMb: Float,
            totalBytes: Long,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("incrementTrafficAndSetBaseline called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                // 1. Increment traffic
                val currentUsage = (prefs[SimSettingsKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                prefs[SimSettingsKeys.trafficUsedKey(identity)] = (currentUsage + deltaMb.toDouble()).toFloat()

                // 2. Update baseline
                prefs[DynamicSettingsKeys.lastTotalBytesKey(identity)] = totalBytes
            }
        }
    }
