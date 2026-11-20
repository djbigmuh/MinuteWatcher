package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.djbigmuh.minutewatcher.core.common.di.SimDataStore
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import com.github.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SimLimitsRepository] using [DataStore].
 *
 * Manages minutes and traffic limits for SIM identities.
 * AOSP DataStore compliant via [BaseDataStoreRepository].
 * Safe to call from any coroutine context.
 */
@Singleton
class SimLimitsRepositoryImpl
    @Inject
    constructor(
        @SimDataStore dataStore: DataStore<Preferences>,
    ) : BaseDataStoreRepository(dataStore),
        SimLimitsRepository {
        // DataStore keys are an implementation detail. The public repository API must not expose them.
        // This complies with the Dependency Inversion Principle.

        /**
         * Returns a flow of the monthly minutes limit for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getMonthlyLimit(identity: String): Flow<Int> = getData(DataStoreKeys.monthlyLimitKey(identity), 0)

        /**
         * Returns a flow of the monthly traffic limit in MB for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getTrafficLimit(identity: String): Flow<Int> = getData(DataStoreKeys.trafficLimitKey(identity), 0)

        /**
         * Updates limits for the given identity.
         *
         * @param minutesLimit Monthly minutes limit.
         * @param trafficLimitMb Monthly traffic limit in MB.
         * @param rolloverEnabled True if unused minutes/traffic should roll over to the next period.
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun updateSimLimits(
            identity: String,
            minutesLimit: Int,
            trafficLimitMb: Int,
            rolloverEnabled: Boolean,
        ) {
            val safeMinutes = minutesLimit.coerceIn(0, Constants.MAX_MINUTES_LIMIT)
            val maxTrafficMb = Constants.MAX_TRAFFIC_LIMIT_MB
            val safeTraffic = trafficLimitMb.coerceIn(0, maxTrafficMb)

            dataStore.edit {
                it[DataStoreKeys.monthlyLimitKey(identity)] = safeMinutes
                it[DataStoreKeys.trafficLimitKey(identity)] = safeTraffic
                it[DataStoreKeys.rolloverEnabledKey(identity)] = rolloverEnabled
            }
        }

        /**
         * Returns a flow indicating if automatic reset is enabled for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getAutoReset(identity: String): Flow<Boolean> =
            getData(DataStoreKeys.autoResetKey(identity), defaultValue = false)

        /**
         * Returns a flow of the day of the month when automatic reset occurs.
         *
         * Safe to call from any coroutine context.
         */
        override fun getAutoResetDay(identity: String): Flow<Int> = getData(DataStoreKeys.autoResetDayKey(identity), 1)

        /**
         * Updates automatic reset settings for the given identity.
         *
         * @param day Day of the month (coerced to range 1..31).
         * @param enabled True if automatic reset is enabled.
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setAutoReset(
            identity: String,
            day: Int,
            enabled: Boolean,
        ) {
            val safeDay = day.coerceIn(Constants.AutoReset.MIN_DAY, Constants.AutoReset.MAX_DAY)
            dataStore.edit {
                it[DataStoreKeys.autoResetDayKey(identity)] = safeDay
                it[DataStoreKeys.autoResetKey(identity)] = enabled
            }
        }
    }
