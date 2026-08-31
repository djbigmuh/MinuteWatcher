package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.di.DataStoreKeys
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
import kotlin.math.roundToInt

/**
 * Implementation of [SimUsageRepository], [SimUsageAdjustmentRepository],
 * and [SimUsageResetRepository] using [DataStore].
 *
 * Tracks and manages call duration and traffic usage for SIM identities.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 */
@Singleton
class SimUsageRepositoryImpl
    @Inject
    constructor(
        @SimDataStore dataStore: DataStore<Preferences>,
        private val timeProvider: TimeProvider,
        private val mapper: SimUsageMapper,
        private val resetLogic: SimUsageResetLogic,
    ) : BaseDataStoreRepository(dataStore),
        SimUsageRepository,
        SimUsageAdjustmentRepository,
        SimUsageResetRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        private val resetMutex = Mutex()

        /**
         * Returns a flow of [SimUsage] for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getSimUsage(identity: String): Flow<SimUsage> {
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("getSimUsage called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(mapper.createEmptyUsage(identity))
            }
            return dataStore.data.map { prefs -> mapper.mapToSimUsage(identity, prefs) }
        }

        /**
         * Sets the absolute value of minutes used for the identity.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setMinutesUsed(
            identity: String,
            value: Int,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setMinutesUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.minutesUsedKey(identity)] = value }
        }

        /**
         * Increments minutes used for the identity by the given delta.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun incrementMinutesUsed(
            identity: String,
            delta: Int,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("incrementMinutesUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                val current = prefs[DataStoreKeys.minutesUsedKey(identity)] ?: 0
                prefs[DataStoreKeys.minutesUsedKey(identity)] = current + delta
            }
        }

        /**
         * Sets the absolute value of traffic used in MB for the identity.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setTrafficUsed(
            identity: String,
            valueMb: Float,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setTrafficUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.trafficUsedKey(identity)] = valueMb }
        }

        /**
         * Increments traffic used in MB for the identity by the given delta.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun incrementTrafficUsed(
            identity: String,
            deltaMb: Float,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("incrementTrafficUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                val current = (prefs[DataStoreKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                val newValue = (current + deltaMb.toDouble()).toFloat().coerceIn(0f, MAX_TRAFFIC_MB)
                val rounded = (newValue * 100).roundToInt() / 100f
                prefs[DataStoreKeys.trafficUsedKey(identity)] = rounded
            }
        }

        /**
         * Sets the manual adjustment for minutes for the identity.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setManualAdjustment(
            identity: String,
            value: Int,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setManualAdjustment called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.manualAdjustmentKey(identity)] = value }
        }

        /**
         * Sets the manual adjustment for traffic in GB for the identity.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setTrafficAdjustment(
            identity: String,
            valueGb: Float,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setTrafficAdjustment called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.trafficAdjustmentKey(identity)] = valueGb }
        }

        /**
         * Updates multiple usage statistics atomically.
         *
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
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
                it[DataStoreKeys.minutesUsedKey(identity)] = minutesUsed
                it[DataStoreKeys.trafficUsedKey(identity)] = trafficUsedMb
                it[DataStoreKeys.manualAdjustmentKey(identity)] = manualAdjustment
            }
        }

        /**
         * Resets SIM usage stats with rollover support.
         *
         * Guaranteed to be atomic via [resetMutex].
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         * @throws kotlinx.coroutines.CancellationException if reset is cancelled.
         */
        override suspend fun resetSimUsage(identity: String) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("resetSimUsage called with invalid identity: $identity")
                return
            }
            resetMutex.withLock {
                dataStore.edit { prefs ->
                    resetLogic.applyResetWithRollover(prefs, identity, timeProvider.now())
                }
            }
        }

        /**
         * Manually resets SIM usage stats to zero without rollover.
         *
         * Guaranteed to be atomic via [resetMutex].
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         * @throws kotlinx.coroutines.CancellationException if reset is cancelled.
         */
        override suspend fun resetSimUsageManual(identity: String) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("resetSimUsageManual called with invalid identity: $identity")
                return
            }
            resetMutex.withLock {
                dataStore.edit { prefs ->
                    prefs[DataStoreKeys.minutesUsedKey(identity)] = 0
                    prefs[DataStoreKeys.trafficUsedKey(identity)] = 0f
                    prefs[DataStoreKeys.manualAdjustmentKey(identity)] = 0
                    prefs[DataStoreKeys.trafficAdjustmentKey(identity)] = 0f
                    prefs[DataStoreKeys.lastResetDateKey(identity)] = timeProvider.now()

                    resetLogic.resetNotificationFlags(prefs, identity)
                }
            }
        }

        /**
         * Resets SIM usage if the billing period has passed.
         *
         * @param autoResetDay Day of month for reset.
         * @param nowMillis Current timestamp.
         * @return True if reset was actually performed.
         * Guaranteed to be atomic via [resetMutex].
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         * @throws kotlinx.coroutines.CancellationException if reset is cancelled.
         */
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
                        prefs[DataStoreKeys.lastResetDateKey(identity)]
                            ?: Constants.Data.EMPTY_TIMESTAMP
                    if (innerLastResetDate < periodStart) {
                        resetLogic.applyResetWithRollover(prefs, identity, nowMillis)
                        actuallyReset = true
                    }
                }
                actuallyReset
            }
        }

        override fun getLastResetDate(identity: String): Flow<Long> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getLastResetDate called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(0L)
            }
            return getData(DataStoreKeys.lastResetDateKey(identity), 0L)
        }

        override suspend fun setLastResetDate(
            identity: String,
            value: Long,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setLastResetDate called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.lastResetDateKey(identity)] = value }
        }

        override fun getLastTotalBytes(identity: String): Flow<Long> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getLastTotalBytes called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(-1L)
            }
            return getData(DataStoreKeys.lastTotalBytesKey(identity), -1L)
        }

        override suspend fun setLastTotalBytes(
            identity: String,
            bytes: Long,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setLastTotalBytes called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.lastTotalBytesKey(identity)] = bytes }
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
                val currentUsage = (prefs[DataStoreKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                val newValue = (currentUsage + deltaMb.toDouble()).toFloat().coerceIn(0f, MAX_TRAFFIC_MB)
                val rounded = (newValue * 100).roundToInt() / 100f
                prefs[DataStoreKeys.trafficUsedKey(identity)] = rounded

                // 2. Update baseline
                prefs[DataStoreKeys.lastTotalBytesKey(identity)] = totalBytes
            }
        }

        companion object {
            /**
             * Максимальное значение трафика в МБ, при котором Float сохраняет
             * точность до 1 МБ. Выше этого значения Float теряет точность.
             * На практике трафик не достигает таких значений за один расчётный
             * период, но защита предотвращает переполнение при ошибке.
             */
            private const val MAX_TRAFFIC_MB = 10_000_000f // ~10 ТБ
        }
    }
