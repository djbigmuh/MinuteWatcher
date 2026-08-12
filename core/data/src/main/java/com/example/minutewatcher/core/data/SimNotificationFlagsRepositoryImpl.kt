package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.di.SimDataStore
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.model.Constants.SimConstants
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimNotificationFlagsRepositoryImpl
    @Inject
    constructor(
        @SimDataStore dataStore: DataStore<Preferences>,
    ) : BaseDataStoreRepository(dataStore),
        SimNotificationFlagsRepository {
        override fun getNotifiedLowMinutes(identity: String): Flow<Boolean> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getNotifiedLowMinutes called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(false)
            }
            return getData(NotificationSettingsKeys.notifiedLowMinutesKey(identity), false)
        }

        override suspend fun setNotifiedLowMinutes(
            identity: String,
            notified: Boolean,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setNotifiedLowMinutes called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[NotificationSettingsKeys.notifiedLowMinutesKey(identity)] = notified }
        }

        override fun getNotifiedLowTraffic(identity: String): Flow<Boolean> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getNotifiedLowTraffic called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(false)
            }
            return getData(NotificationSettingsKeys.notifiedLowTrafficKey(identity), false)
        }

        override suspend fun setNotifiedLowTraffic(
            identity: String,
            notified: Boolean,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setNotifiedLowTraffic called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[NotificationSettingsKeys.notifiedLowTrafficKey(identity)] = notified }
        }

        override fun getNotifiedExhaustedMinutes(identity: String): Flow<Boolean> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getNotifiedExhaustedMinutes called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(false)
            }
            return getData(NotificationSettingsKeys.notifiedExhaustedMinutesKey(identity), false)
        }

        override suspend fun setNotifiedExhaustedMinutes(
            identity: String,
            notified: Boolean,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setNotifiedExhaustedMinutes called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[NotificationSettingsKeys.notifiedExhaustedMinutesKey(identity)] = notified }
        }

        override fun getNotifiedExhaustedTraffic(identity: String): Flow<Boolean> {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("getNotifiedExhaustedTraffic called with invalid identity: $identity")
                return kotlinx.coroutines.flow.flowOf(false)
            }
            return getData(NotificationSettingsKeys.notifiedExhaustedTrafficKey(identity), false)
        }

        override suspend fun setNotifiedExhaustedTraffic(
            identity: String,
            notified: Boolean,
        ) {
            if (identity.startsWith(SimConstants.INVALID_SLOT_IDENTITY_PREFIX)) {
                AppLogger.w("setNotifiedExhaustedTraffic called with invalid identity: $identity")
                return
            }
            dataStore.edit { it[NotificationSettingsKeys.notifiedExhaustedTrafficKey(identity)] = notified }
        }
    }
