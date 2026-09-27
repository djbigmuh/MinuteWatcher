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
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator
import kotlinx.coroutines.flow.Flow
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
        // DataStore keys are an implementation detail. The public repository API must not expose them.
        // This complies with the Dependency Inversion Principle.

        private val resetMutex = Mutex()

        /**
         * Returns a flow of [SimUsage] for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getSimUsage(identity: String): Flow<SimUsage> =
            dataStore.data.map { prefs -> mapper.mapToSimUsage(identity, prefs) }

        /**
         * Increments minutes used for the identity by the given delta for a specific call.
         *
         * Idempotent implementation using [callSignature]. If the signature matches the saved one,
         * no changes are made and false is returned. Deprecated numeric marker is removed upon
         * first successful call recording for the given identity.
         *
         * The [callSignature] is composed of session start time (wall clock)
         * and session start moment (elapsedRealtime).
         *
         * Safe to call from any coroutine context.
         * @return True if incremented, false if already counted or identity is invalid.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun incrementMinutesUsedForCall(
            identity: String,
            deltaMinutes: Int,
            callSignature: String,
        ): Boolean {
            var result = false
            dataStore.edit { prefs ->
                val lastSignature = prefs[DataStoreKeys.lastCountedCallSignatureKey(identity)]
                if (lastSignature == callSignature) {
                    AppLogger.d("incrementMinutesUsedForCall: call $callSignature already counted for $identity")
                    result = false
                    return@edit
                }

                val current = prefs[DataStoreKeys.minutesUsedKey(identity)] ?: 0
                prefs[DataStoreKeys.minutesUsedKey(identity)] = current + deltaMinutes
                prefs[DataStoreKeys.lastCountedCallSignatureKey(identity)] = callSignature
                prefs.remove(DataStoreKeys.lastCountedCallMarkerKey(identity))
                result = true
            }
            return result
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
            resetMutex.withLock {
                dataStore.edit { prefs ->
                    resetLogic.applyResetWithRollover(prefs, identity, timeProvider.now())
                }
            }
        }

        /**
         * Manually resets SIM usage stats to zero without rollover, zeroing manual adjustments as well.
         * See [SimUsage.manualAdjustment].
         *
         * Guaranteed to be atomic via [resetMutex].
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         * @throws kotlinx.coroutines.CancellationException if reset is cancelled.
         */
        override suspend fun resetSimUsageManual(identity: String) {
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
         * Resets SIM usage if the billing period has passed, consuming adjustments according to the rollover setting.
         * See [SimUsage.manualAdjustment].
         *
         * If the last reset date is not recorded ([Constants.Data.EMPTY_TIMESTAMP]),
         * the current billing window is opened at [nowMillis] without clearing usage.
         * Subsequent checks will trigger reset only after the period boundary.
         *
         * @param autoResetDay Day of month for reset.
         * @param nowMillis Current timestamp.
         * @return True if reset was actually performed (statistics cleared).
         * Guaranteed to be atomic via [resetMutex].
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         * @throws kotlinx.coroutines.CancellationException if reset is cancelled.
         */
        override suspend fun resetSimUsageIfDue(
            identity: String,
            autoResetDay: Int,
            nowMillis: Long,
        ): Boolean =
            resetMutex.withLock {
                val periodStart = BillingPeriodCalculator.calculate(autoResetDay, nowMillis).startDate

                var actuallyReset = false
                dataStore.edit { prefs ->
                    val innerLastResetDate =
                        prefs[DataStoreKeys.lastResetDateKey(identity)]
                            ?: Constants.Data.EMPTY_TIMESTAMP

                    if (innerLastResetDate == Constants.Data.EMPTY_TIMESTAMP) {
                        prefs[DataStoreKeys.lastResetDateKey(identity)] = nowMillis
                        actuallyReset = false
                    } else if (innerLastResetDate < periodStart) {
                        resetLogic.applyResetWithRollover(prefs, identity, nowMillis)
                        actuallyReset = true
                    }
                }
                actuallyReset
            }

        override fun getLastTotalBytes(identity: String): Flow<Long> =
            getData(DataStoreKeys.lastTotalBytesKey(identity), -1L)

        override suspend fun setLastTotalBytes(
            identity: String,
            bytes: Long,
        ) {
            dataStore.edit { it[DataStoreKeys.lastTotalBytesKey(identity)] = bytes }
        }

        override suspend fun incrementTrafficAndSetBaseline(
            identity: String,
            deltaMb: Float,
            totalBytes: Long,
        ) {
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

        override suspend fun ensureTrackingWindowInitialized(identity: String): Boolean =
            resetMutex.withLock {
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

        override fun getBaselineRetryCount(identity: String): Flow<Int?> =
            dataStore.data.map { it[DataStoreKeys.baselineRetryCountKey(identity)] }

        override suspend fun setBaselineRetryCount(
            identity: String,
            count: Int?,
        ) {
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
             * Maximum traffic value in MB at which Float retains
             * precision up to 1 MB. Above this value Float loses precision.
             * In practice traffic does not reach such values in a single billing
             * period, but the check guards against overflow on error.
             */
            private const val MAX_TRAFFIC_MB = 10_000_000f // ~10 TB

            private const val TRAFFIC_ROUNDING_FACTOR = 100.0
        }
    }
