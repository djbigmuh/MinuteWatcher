package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimLimitsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimLimitsRepositoryImpl @Inject constructor(
    @SimDataStore private val dataStore: DataStore<Preferences>,
) : SimLimitsRepository {

    private fun <T> get(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

    override fun getMonthlyLimit(identity: String): Flow<Int> = get(SimSettingsKeys.monthlyLimitKey(identity), 0)

    override fun getTrafficLimit(identity: String): Flow<Int> = get(SimSettingsKeys.trafficLimitKey(identity), 0)

    override suspend fun updateSimLimits(
        identity: String,
        minutesLimit: Int,
        trafficLimitMb: Int,
        rolloverEnabled: Boolean
    ) {
        dataStore.edit {
            it[SimSettingsKeys.monthlyLimitKey(identity)] = minutesLimit
            it[SimSettingsKeys.trafficLimitKey(identity)] = trafficLimitMb
            it[SimSettingsKeys.rolloverEnabledKey(identity)] = rolloverEnabled
        }
    }

    override fun getAutoReset(identity: String): Flow<Boolean> = get(SimSettingsKeys.autoResetKey(identity), false)

    override fun getAutoResetDay(identity: String): Flow<Int> = get(SimSettingsKeys.autoResetDayKey(identity), 1)

    override suspend fun setAutoReset(identity: String, day: Int, enabled: Boolean) {
        dataStore.edit {
            it[SimSettingsKeys.autoResetDayKey(identity)] = day
            it[SimSettingsKeys.autoResetKey(identity)] = enabled
        }
    }
}
