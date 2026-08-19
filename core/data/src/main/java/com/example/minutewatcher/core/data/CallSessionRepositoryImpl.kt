package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.data.di.AppDataStore
import com.example.minutewatcher.core.domain.CallSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallSessionRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
    ) : CallSessionRepository {
        // Preferences.Key is a DataStore implementation detail.
        // It MUST NOT leak into the domain layer (SettingsRepository interface).
        // Domain operates on business models only; mapping is an Impl responsibility.

        private fun <T> get(
            key: Preferences.Key<T>,
            defaultValue: T,
        ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

        override fun getLastDataSubId(): Flow<Int> = get(SettingsKeys.LAST_DATA_SUB_ID, -1)

        override suspend fun setLastDataSubId(subId: Int) {
            dataStore.edit { it[SettingsKeys.LAST_DATA_SUB_ID] = subId }
        }

        override fun getCallStartTime(subId: Int): Flow<Long> = get(DynamicSettingsKeys.callStartTimeKey(subId), 0L)

        override suspend fun setCallStartTime(
            subId: Int,
            time: Long,
        ) {
            dataStore.edit { it[DynamicSettingsKeys.callStartTimeKey(subId)] = time }
        }

        override fun getBootTime(subId: Int): Flow<Long> = get(DynamicSettingsKeys.bootTimeKey(subId), 0L)

        override suspend fun setBootTime(
            subId: Int,
            time: Long,
        ) {
            dataStore.edit { it[DynamicSettingsKeys.bootTimeKey(subId)] = time }
        }

        override fun getUptime(subId: Int): Flow<Long> = get(DynamicSettingsKeys.uptimeKey(subId), 0L)

        override suspend fun setUptime(
            subId: Int,
            time: Long,
        ) {
            dataStore.edit { it[DynamicSettingsKeys.uptimeKey(subId)] = time }
        }

        override fun getCallIdentity(subId: Int): Flow<String?> =
            dataStore.data.map {
                it[DynamicSettingsKeys.callIdentityKey(subId)]
            }

        override suspend fun setCallIdentity(
            subId: Int,
            identity: String?,
        ) {
            dataStore.edit {
                if (identity == null) {
                    it.remove(DynamicSettingsKeys.callIdentityKey(subId))
                } else {
                    it[DynamicSettingsKeys.callIdentityKey(subId)] = identity
                }
            }
        }

        override suspend fun clearAllCallSessions() {
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { key ->
                        key.name.startsWith(DynamicSettingsKeys.CALL_START_TIME_PREFIX) ||
                            key.name.startsWith(DynamicSettingsKeys.BOOT_TIME_PREFIX) ||
                            key.name.startsWith(DynamicSettingsKeys.UPTIME_PREFIX) ||
                            key.name.startsWith(DynamicSettingsKeys.CALL_IDENTITY_PREFIX)
                    }.forEach { key ->
                        prefs.remove(key)
                    }
            }
        }

        override suspend fun getActiveCallSubIds(): List<Int> =
            dataStore.data.first().asMap().entries.mapNotNull { (key, value) ->
                if (key.name.startsWith(DynamicSettingsKeys.CALL_START_TIME_PREFIX) && (value as? Long ?: 0L) > 0L) {
                    key.name.removePrefix(DynamicSettingsKeys.CALL_START_TIME_PREFIX).toIntOrNull()
                } else {
                    null
                }
            }
    }
