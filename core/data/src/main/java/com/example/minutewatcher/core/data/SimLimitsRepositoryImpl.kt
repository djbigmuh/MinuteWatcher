package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SimLimitsRepository] using [DataStore].
 *
 * Manages minutes and traffic limits for SIM identities.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 */
@Singleton
class SimLimitsRepositoryImpl
    @Inject
    constructor(
        @SimDataStore private val dataStore: DataStore<Preferences>,
    ) : SimLimitsRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        private fun <T> get(
            key: Preferences.Key<T>,
            defaultValue: T,
        ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

        /**
         * Returns a flow of the monthly minutes limit for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getMonthlyLimit(identity: String): Flow<Int> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getMonthlyLimit called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(0)
            }
            return get(DataStoreKeys.monthlyLimitKey(identity), 0)
        }

        /**
         * Returns a flow of the monthly traffic limit in MB for the given identity.
         *
         * Safe to call from any coroutine context.
         */
        override fun getTrafficLimit(identity: String): Flow<Int> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getTrafficLimit called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(0)
            }
            return get(DataStoreKeys.trafficLimitKey(identity), 0)
        }

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
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("updateSimLimits called with invalid identity: $identity")
                return
            }
            val safeMinutes = minutesLimit.coerceIn(0, Constants.MAX_MINUTES_LIMIT)
            val maxTrafficMb = (Constants.MAX_TRAFFIC_LIMIT_GB * Constants.MB_PER_GB).toInt()
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
        override fun getAutoReset(identity: String): Flow<Boolean> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getAutoReset called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(false)
            }
            return get(DataStoreKeys.autoResetKey(identity), false)
        }

        /**
         * Returns a flow of the day of the month when automatic reset occurs.
         *
         * Safe to call from any coroutine context.
         */
        override fun getAutoResetDay(identity: String): Flow<Int> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getAutoResetDay called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(1)
            }
            return get(DataStoreKeys.autoResetDayKey(identity), 1)
        }

        /**
         * Updates automatic reset settings for the given identity.
         *
         * @param day Day of the month (1-31).
         * @param enabled True if automatic reset is enabled.
         * Safe to call from any coroutine context.
         * @throws java.io.IOException if write fails.
         */
        override suspend fun setAutoReset(
            identity: String,
            day: Int,
            enabled: Boolean,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setAutoReset called with invalid identity: $identity")
                return
            }
            dataStore.edit {
                it[DataStoreKeys.autoResetDayKey(identity)] = day
                it[DataStoreKeys.autoResetKey(identity)] = enabled
            }
        }
    }
