package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.SimDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Implementation of [SimUsageRepository], [SimUsageAdjustmentRepository],
 * and [SimUsageResetRepository] using [DataStore].
 *
 * Tracks and manages call duration and traffic usage for SIM identities.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 *
 * NOTE: Suppressing TooManyFunctions as this repository consolidates all usage-related persistence logic
 * for calls and traffic, implementing multiple domain interfaces for a unified DataStore source.
 */
@Singleton
@Suppress("TooManyFunctions")
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
                return flowOf(mapper.createEmptyUsage(identity))
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
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("setMinutesUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.minutesUsedKey(identity)] = value }
        }

        /**
         * Increments minutes used for the identity by the given delta for a specific call.
         *
         * Idempotent implementation using [callMarker]. If the marker matches the saved one,
         * no changes are made and false is returned.
         *
         * The [callMarker] is the session's bootTime (elapsedRealtime of the call start).
         * Marker collisions between different boots are theoretically possible only if
         * elapsedRealtime matches exactly, which is handled as a conservative skip.
         *
         * Safe to call from any coroutine context.
         * @return True if incremented, false if already counted or identity is invalid.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun incrementMinutesUsedForCall(
            identity: String,
            deltaMinutes: Int,
            callMarker: Long,
        ): Boolean {
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("incrementMinutesUsedForCall called with invalid identity: $identity")
                return false
            }
            var result = false
            dataStore.edit { prefs ->
                val lastMarker = prefs[DataStoreKeys.lastCountedCallMarkerKey(identity)] ?: 0L
                if (lastMarker == callMarker) {
                    AppLogger.d("incrementMinutesUsedForCall: call $callMarker already counted for $identity")
                    result = false
                    return@edit
                }

                val current = prefs[DataStoreKeys.minutesUsedKey(identity)] ?: 0
                prefs[DataStoreKeys.minutesUsedKey(identity)] = current + deltaMinutes
                prefs[DataStoreKeys.lastCountedCallMarkerKey(identity)] = callMarker
                result = true
            }
            return result
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
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("incrementTrafficUsed called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                val current = (prefs[DataStoreKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                val newValue = current + deltaMb.toDouble()
                val rounded = ((newValue * TRAFFIC_ROUNDING_FACTOR).roundToLong() / TRAFFIC_ROUNDING_FACTOR).toFloat()
                prefs[DataStoreKeys.trafficUsedKey(identity)] = rounded.coerceIn(0f, MAX_TRAFFIC_MB)
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
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("getLastResetDate called with invalid identity: $identity")
                return flowOf(0L)
            }
            return getData(DataStoreKeys.lastResetDateKey(identity), 0L)
        }

        override suspend fun setLastResetDate(
            identity: String,
            value: Long,
        ) {
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("setLastResetDate called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[DataStoreKeys.lastResetDateKey(identity)] = value }
        }

        override fun getLastTotalBytes(identity: String): Flow<Long> {
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("getLastTotalBytes called with invalid identity: $identity")
                return flowOf(-1L)
            }
            return getData(DataStoreKeys.lastTotalBytesKey(identity), -1L)
        }

        override suspend fun setLastTotalBytes(
            identity: String,
            bytes: Long,
        ) {
            if (SimConstants.isInvalid(identity)) {
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
            if (SimConstants.isInvalid(identity)) {
                AppLogger.w("incrementTrafficAndSetBaseline called with invalid identity: $identity")
                return
            }
            dataStore.edit { prefs ->
                // 1. Increment traffic
                val currentUsage = (prefs[DataStoreKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                val newValue = currentUsage + deltaMb.toDouble()
                val rounded = ((newValue * TRAFFIC_ROUNDING_FACTOR).roundToLong() / TRAFFIC_ROUNDING_FACTOR).toFloat()
                prefs[DataStoreKeys.trafficUsedKey(identity)] = rounded.coerceIn(0f, MAX_TRAFFIC_MB)

                // 2. Update baseline
                prefs[DataStoreKeys.lastTotalBytesKey(identity)] = totalBytes
            }
        }

        override suspend fun ensureTrackingWindowInitialized(identity: String): Boolean {
            if (SimConstants.isInvalid(identity)) return false

            return resetMutex.withLock {
                var initialized = false
                dataStore.edit { prefs ->
                    val lastResetDate = prefs[DataStoreKeys.lastResetDateKey(identity)] ?: 0L
                    if (lastResetDate == 0L) {
                        val now = timeProvider.now()
                        prefs[DataStoreKeys.lastResetDateKey(identity)] = now
                        // Window starts now, so initial total bytes in [now, now] is 0.
                        prefs[DataStoreKeys.lastTotalBytesKey(identity)] = 0L
                        AppLogger.d("Initialized tracking window for $identity: lastResetDate=$now, baseline=0")
                        initialized = true
                    }
                }
                initialized
            }
        }

        override fun getBaselineRetryCount(identity: String): Flow<Int?> {
            if (SimConstants.isInvalid(identity)) return flowOf(null)
            return dataStore.data.map { it[DataStoreKeys.baselineRetryCountKey(identity)] }
        }

        override suspend fun setBaselineRetryCount(
            identity: String,
            count: Int?,
        ) {
            if (SimConstants.isInvalid(identity)) return
            dataStore.edit { prefs ->
                val key = DataStoreKeys.baselineRetryCountKey(identity)
                if (count == null) {
                    prefs.remove(key)
                } else {
                    prefs[key] = count
                }
            }
        }

        override suspend fun cleanupBaselineRetries(activeIdentities: Set<String>) {
            dataStore.edit { prefs ->
                val allKeys = prefs.asMap().keys.toList() // Avoid concurrent modification
                val prefix = DataStoreKeys.BASELINE_RETRY_COUNT_PREFIX
                allKeys.forEach { key ->
                    if (key.name.startsWith(prefix)) {
                        val identity = key.name.removePrefix(prefix)
                        if (identity !in activeIdentities) {
                            prefs.remove(key)
                        }
                    }
                }
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

            private const val TRAFFIC_ROUNDING_FACTOR = 100.0
        }
    }
