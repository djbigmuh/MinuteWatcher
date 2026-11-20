package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.djbigmuh.minutewatcher.core.common.di.SimDataStore
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.SimUsage
import com.github.djbigmuh.minutewatcher.core.model.util.BillingPeriodCalculator
import com.github.djbigmuh.minutewatcher.core.model.util.UnitConversionUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

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
            if (identity.isBlank() || deltaMinutes <= 0) {
                return false
            }
            var result = false
            dataStore.edit { prefs ->
                val lastSignature = prefs[DataStoreKeys.lastCountedCallSignatureKey(identity)]
                if (lastSignature == callSignature) {
                    AppLogger.d("incrementMinutesUsedForCall: call $callSignature already counted for $identity")
                    result = false
                    return@edit
                }

                val current = prefs[DataStoreKeys.minutesUsedKey(identity)] ?: 0
                val newMinutes = (current + deltaMinutes).coerceIn(0, Constants.MAX_MINUTES_LIMIT)
                prefs[DataStoreKeys.minutesUsedKey(identity)] = newMinutes
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
                it[DataStoreKeys.minutesUsedKey(identity)] = minutesUsed.coerceIn(0, Constants.MAX_MINUTES_LIMIT)
                it[DataStoreKeys.trafficUsedKey(identity)] = trafficUsedMb
                it[DataStoreKeys.manualAdjustmentKey(identity)] = manualAdjustment
            }
        }

        /**
         * Manually resets SIM usage stats to zero without rollover, zeroing manual adjustments as well.
         * See [SimUsage.manualAdjustment].
         *
         * DataStore.edit provides atomicity within the transaction block. Note that [resetMutex]
         * is acquired around the external block to serialize concurrent reset/initialization operations
         * across threads/coroutines.
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         * @throws kotlinx.coroutines.CancellationException if reset is canceled.
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
         * DataStore.edit provides atomicity within the transaction block. Note that [resetMutex]
         * is acquired around the external block to serialize concurrent reset/initialization operations
         * across threads/coroutines.
         *
         * @param autoResetDay Day of month for reset.
         * @param nowMillis Current timestamp.
         * @return True if reset was actually performed (statistics cleared).
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         * @throws kotlinx.coroutines.CancellationException if reset is canceled.
         */
        override suspend fun resetSimUsageIfDue(
            identity: String,
            autoResetDay: Int,
            nowMillis: Long,
        ): Boolean =
            resetMutex.withLock {
                if (nowMillis < Constants.Data.MIN_VALID_TIMESTAMP) {
                    AppLogger.w(
                        "SimUsageRepositoryImpl: Skipped reset check for $identity " +
                            "due to un-synchronized device time ($nowMillis)",
                    )
                    return false
                }

                val zoneId = timeProvider.zoneId()
                val periodStart =
                    BillingPeriodCalculator
                        .calculate(
                            autoResetDay,
                            nowMillis,
                            zoneId,
                        ).startDate

                var actuallyReset = false
                dataStore.edit { prefs ->
                    val innerLastResetDate =
                        prefs[DataStoreKeys.lastResetDateKey(identity)]
                            ?: Constants.Data.EMPTY_TIMESTAMP

                    if (innerLastResetDate != Constants.Data.EMPTY_TIMESTAMP &&
                        nowMillis < innerLastResetDate
                    ) {
                        AppLogger.w(
                            "SimUsageRepositoryImpl: Clock rollback detected for $identity " +
                                "(now=$nowMillis < lastReset=$innerLastResetDate), skipping reset",
                        )
                        return@edit
                    }

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
            expectedLastResetDate: Long,
            subId: Int,
        ): Boolean {
            var applied = false
            dataStore.edit { prefs ->
                val currentResetDate = prefs[DataStoreKeys.lastResetDateKey(identity)] ?: Constants.Data.EMPTY_TIMESTAMP
                if (isWindowCompatible(currentResetDate, expectedLastResetDate)) {
                    prefs[DataStoreKeys.lastTotalBytesKey(identity)] = bytes
                    if (subId != -1) {
                        prefs[DataStoreKeys.subscriptionIdKey(identity)] = subId
                    }
                    applied = true
                }
            }
            return applied
        }

        override suspend fun incrementTrafficAndSetBaseline(
            identity: String,
            deltaMb: Float,
            totalBytes: Long,
            expectedLastResetDate: Long,
            subId: Int,
        ): Boolean {
            if (deltaMb <= 0f) return false
            var applied = false
            dataStore.edit { prefs ->
                val currentResetDate =
                    prefs[DataStoreKeys.lastResetDateKey(identity)]
                        ?: Constants.Data.EMPTY_TIMESTAMP
                if (isWindowCompatible(currentResetDate, expectedLastResetDate)) {
                    // 1. Increment traffic
                    val currentUsage = (prefs[DataStoreKeys.trafficUsedKey(identity)] ?: 0f).toDouble()
                    val newValue = currentUsage + deltaMb.toDouble()
                    val rounded = UnitConversionUtils.roundTrafficMb(newValue.toFloat())
                    prefs[DataStoreKeys.trafficUsedKey(identity)] =
                        rounded.coerceIn(0f, Constants.MAX_TRAFFIC_LIMIT_MB.toFloat())

                    // 2. Update baseline
                    prefs[DataStoreKeys.lastTotalBytesKey(identity)] = totalBytes
                    if (subId != -1) {
                        prefs[DataStoreKeys.subscriptionIdKey(identity)] = subId
                    }
                    applied = true
                }
            }
            return applied
        }

        private fun isWindowCompatible(
            currentResetDate: Long,
            expectedLastResetDate: Long,
        ): Boolean =
            currentResetDate == expectedLastResetDate ||
                expectedLastResetDate == Constants.Data.EMPTY_TIMESTAMP ||
                currentResetDate == Constants.Data.EMPTY_TIMESTAMP

        /**
         * Ensures tracking window is initialized.
         *
         * DataStore.edit provides atomicity within the transaction block. Note that [resetMutex]
         * is acquired around the external block to serialize concurrent reset/initialization operations
         * across threads/coroutines.
         * Safe to call from any coroutine context.
         */
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
    }
