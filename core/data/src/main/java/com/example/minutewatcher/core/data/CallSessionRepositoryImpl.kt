package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.common.util.firstWithTimeout
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [CallSessionRepository] using [DataStore].
 *
 * Tracks transient call session data (start times, identities) for attribution.
 * AOSP DataStore compliant.
 * Safe to call from any coroutine context.
 *
 * NOTE: This repository manages all transient call session metadata
 * for multiple SIM slots, including start times, identities, and boot state markers.
 */
@Singleton
class CallSessionRepositoryImpl
    @Inject
    constructor(
        @AppDataStore private val dataStore: DataStore<Preferences>,
    ) : CallSessionRepository {
        // Ключи DataStore — деталь реализации. Публичный API репозитория не должен их раскрывать.
        // Это соответствует принципу Dependency Inversion.

        private fun <T> get(
            key: Preferences.Key<T>,
            defaultValue: T,
        ): Flow<T> = dataStore.data.map { it[key] ?: defaultValue }

        /**
         * Returns the subId of the last used data SIM.
         *
         * Safe to call from any coroutine context.
         */
        override fun getLastDataSubId(): Flow<Int> = get(DataStoreKeys.LAST_DATA_SUB_ID, -1)

        /**
         * Updates the subId of the last used data SIM.
         *
         * Safe to call from any coroutine context.
         * @throws IOException если DataStore не может выполнить запись (пробрасывается из dataStore.edit).
         */
        override suspend fun setLastDataSubId(subId: Int) {
            dataStore.edit { it[DataStoreKeys.LAST_DATA_SUB_ID] = subId }
        }

        /**
         * Atomically saves all call session data for the given subId.
         *
         * @throws IOException если DataStore не может выполнить запись.
         */
        override suspend fun saveCallSession(
            subId: Int,
            startTime: Long,
            bootTime: Long,
            uptime: Long,
            identity: String?,
        ) {
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.callStartTimeKey(subId)] = startTime
                prefs[DataStoreKeys.bootTimeKey(subId)] = bootTime
                prefs[DataStoreKeys.uptimeKey(subId)] = uptime
                val identityKey = DataStoreKeys.callIdentityKey(subId)
                if (identity == null) {
                    prefs.remove(identityKey)
                } else {
                    prefs[identityKey] = identity
                }
            }
        }

        /**
         * Atomically clears all call session keys for the given subId.
         *
         * @throws IOException если DataStore не может выполнить запись.
         */
        override suspend fun clearCallSession(subId: Int) {
            dataStore.edit { prefs ->
                prefs.remove(DataStoreKeys.callStartTimeKey(subId))
                prefs.remove(DataStoreKeys.bootTimeKey(subId))
                prefs.remove(DataStoreKeys.uptimeKey(subId))
                prefs.remove(DataStoreKeys.callIdentityKey(subId))
            }
        }

        /**
         * Returns the call start time for the given subId.
         *
         * Safe to call from any coroutine context.
         */
        override fun getCallStartTime(subId: Int): Flow<Long> = get(DataStoreKeys.callStartTimeKey(subId), 0L)

        /**
         * Returns the device boot time recorded when the call started on the given subId.
         *
         * Safe to call from any coroutine context.
         */
        override fun getBootTime(subId: Int): Flow<Long> = get(DataStoreKeys.bootTimeKey(subId), 0L)

        /**
         * Returns the device uptime recorded when the call started on the given subId.
         *
         * Safe to call from any coroutine context.
         */
        override fun getUptime(subId: Int): Flow<Long> = get(DataStoreKeys.uptimeKey(subId), 0L)

        /**
         * Returns the stable SIM identity associated with the active call on the given subId.
         *
         * Safe to call from any coroutine context.
         */
        override fun getCallIdentity(subId: Int): Flow<String?> =
            dataStore.data.map {
                it[DataStoreKeys.callIdentityKey(subId)]
            }

        /**
         * Clears all recorded call session data.
         *
         * Safe to call from any coroutine context.
         * @throws IOException если DataStore не может выполнить запись (пробрасывается из dataStore.edit).
         */
        override suspend fun clearAllCallSessions() {
            dataStore.edit { prefs ->
                prefs
                    .asMap()
                    .keys
                    .filter { key ->
                        key.name.startsWith(DataStoreKeys.CALL_START_TIME_PREFIX) ||
                            key.name.startsWith(DataStoreKeys.BOOT_TIME_PREFIX) ||
                            key.name.startsWith(DataStoreKeys.UPTIME_PREFIX) ||
                            key.name.startsWith(DataStoreKeys.CALL_IDENTITY_PREFIX)
                    }.forEach { key ->
                        prefs.remove(key)
                    }
            }
        }

        /**
         * Returns a list of subscription IDs that currently have active call sessions.
         *
         * Safe to call from any coroutine context.
         * @return List of active subIds, or null if reading timed out.
         */
        override suspend fun getActiveCallSubIds(): List<Int>? {
            val prefs = dataStore.data.firstWithTimeout(Constants.Flow.SHORT_TIMEOUT_MS) ?: return null
            return prefs.asMap().entries.mapNotNull { (key, value) ->
                if ((key.name.startsWith(DataStoreKeys.CALL_START_TIME_PREFIX)) && (((value as? Long) ?: 0L) > 0L)) {
                    key.name.removePrefix(DataStoreKeys.CALL_START_TIME_PREFIX).toIntOrNull()
                } else {
                    null
                }
            }
        }
    }
