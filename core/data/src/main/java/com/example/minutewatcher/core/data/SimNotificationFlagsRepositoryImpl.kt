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
class SimNotificationFlagsRepositoryImpl
    @Inject
    constructor(
        @SimDataStore dataStore: DataStore<Preferences>,
    ) : BaseDataStoreRepository(dataStore),
        SimNotificationFlagsRepository {
        override fun getNotifiedLowMinutes(identity: String): Flow<Boolean> =
            getData(NotificationSettingsKeys.notifiedLowMinutesKey(identity), false)

        override suspend fun setNotifiedLowMinutes(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit { it[NotificationSettingsKeys.notifiedLowMinutesKey(identity)] = notified }
        }

        override fun getNotifiedLowTraffic(identity: String): Flow<Boolean> =
            getData(NotificationSettingsKeys.notifiedLowTrafficKey(identity), false)

        override suspend fun setNotifiedLowTraffic(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit { it[NotificationSettingsKeys.notifiedLowTrafficKey(identity)] = notified }
        }

        override fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean> =
            getData(NotificationSettingsKeys.notifiedExhaustedMinutesKey(identity), false)

        override suspend fun setNotifiedExhaustedMinutes(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit { it[NotificationSettingsKeys.notifiedExhaustedMinutesKey(identity)] = notified }
        }

        override fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean> =
            getData(NotificationSettingsKeys.notifiedExhaustedTrafficKey(identity), false)

        override suspend fun setNotifiedExhaustedTraffic(
            identity: String,
            notified: Boolean,
        ) {
            dataStore.edit { it[NotificationSettingsKeys.notifiedExhaustedTrafficKey(identity)] = notified }
        }
    }
