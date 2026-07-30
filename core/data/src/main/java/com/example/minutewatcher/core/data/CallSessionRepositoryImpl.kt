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
class CallSessionRepositoryImpl @Inject constructor(
    @AppDataStore private val dataStore: DataStore<Preferences>,
) : CallSessionRepository {

    private fun <T> get(
        key: Preferences.Key<T>,
        defaultValue: T,
    ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

    override fun getLastDataSubId(): Flow<Int> = get(SettingsKeys.LAST_DATA_SUB_ID, -1)

    override suspend fun setLastDataSubId(subId: Int) {
        dataStore.edit { it[SettingsKeys.LAST_DATA_SUB_ID] = subId }
    }

    override fun getCallStartTime(subId: Int): Flow<Long> = get(SettingsKeys.callStartTimeKey(subId), 0L)

    override suspend fun setCallStartTime(subId: Int, time: Long) {
        dataStore.edit { it[SettingsKeys.callStartTimeKey(subId)] = time }
    }

    override fun getBootTime(subId: Int): Flow<Long> = get(SettingsKeys.bootTimeKey(subId), 0L)

    override suspend fun setBootTime(subId: Int, time: Long) {
        dataStore.edit { it[SettingsKeys.bootTimeKey(subId)] = time }
    }

    override fun getCallIdentity(subId: Int): Flow<String?> = dataStore.data.map { it[SettingsKeys.callIdentityKey(subId)] }

    override suspend fun setCallIdentity(subId: Int, identity: String?) {
        dataStore.edit {
            if (identity == null) {
                it.remove(SettingsKeys.callIdentityKey(subId))
            } else {
                it[SettingsKeys.callIdentityKey(subId)] = identity
            }
        }
    }

    override suspend fun clearAllCallSessions() {
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { key ->
                    key.name.startsWith("call_start_time_") ||
                        key.name.startsWith("boot_time_") ||
                        key.name.startsWith("call_identity_")
                }.forEach { key ->
                    prefs.remove(key)
                }
        }
    }

    override suspend fun getActiveCallSubIds(): List<Int> =
        dataStore.data.first().asMap().entries.mapNotNull { (key, value) ->
            if (key.name.startsWith("call_start_time_") && (value as? Long ?: 0L) > 0L) {
                key.name.substringAfterLast('_').toIntOrNull()
            } else {
                null
            }
        }
}
