package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimNotificationFlagsRepositoryImpl @Inject constructor(
    @SimDataStore private val dataStore: DataStore<Preferences>,
) : SimNotificationFlagsRepository {

    @Suppress("SameParameterValue")
    private fun <T> get(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

    override fun getNotifiedLowMinutes(identity: String): Flow<Boolean> = get(SettingsKeys.notifiedLowMinutesKey(identity), false)

    override suspend fun setNotifiedLowMinutes(identity: String, notified: Boolean) {
        dataStore.edit { it[SettingsKeys.notifiedLowMinutesKey(identity)] = notified }
    }

    override fun getNotifiedLowTraffic(identity: String): Flow<Boolean> = get(SettingsKeys.notifiedLowTrafficKey(identity), false)

    override suspend fun setNotifiedLowTraffic(identity: String, notified: Boolean) {
        dataStore.edit { it[SettingsKeys.notifiedLowTrafficKey(identity)] = notified }
    }

    override fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean> =
        get(SettingsKeys.notifiedExhaustedMinutesKey(identity), false)

    override suspend fun setNotifiedExhaustedMinutes(identity: String, notified: Boolean) {
        dataStore.edit { it[SettingsKeys.notifiedExhaustedMinutesKey(identity)] = notified }
    }

    override fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean> =
        get(SettingsKeys.notifiedExhaustedTrafficKey(identity), false)

    override suspend fun setNotifiedExhaustedTraffic(identity: String, notified: Boolean) {
        dataStore.edit { it[SettingsKeys.notifiedExhaustedTrafficKey(identity)] = notified }
    }
}
