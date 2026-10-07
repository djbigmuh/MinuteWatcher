package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.di.AppDataStore
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.firstWithTimeoutOrLog
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of [CallSessionRepository] using [DataStore].
 *
 * Tracks transient call session data (start times, identities) for attribution.
 * AOSP DataStore compliant via [BaseDataStoreRepository].
 * Safe to call from any coroutine context.
 *
 * NOTE: This repository manages all transient call session metadata
 * for multiple SIM slots, including start times, identities, and boot state markers.
 */
@Singleton
class CallSessionRepositoryImpl
    @Inject
    constructor(
        @AppDataStore dataStore: DataStore<Preferences>,
    ) : BaseDataStoreRepository(dataStore),
        CallSessionRepository {
        // DataStore keys are an implementation detail. The public repository API must not expose them.
        // This complies with the Dependency Inversion Principle.

        /**
         * Returns the subId of the last used data SIM.
         *
         * Safe to call from any coroutine context.
         */
        override fun getLastDataSubId(): Flow<Int> = getData(DataStoreKeys.LAST_DATA_SUB_ID, -1)

        /**
         * Updates the subId of the last used data SIM.
         *
         * Safe to call from any coroutine context.
         * @throws IOException if DataStore write fails (thrown from dataStore.edit).
         */
        override suspend fun setLastDataSubId(subId: Int) {
            val result =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataStore.edit { it[DataStoreKeys.LAST_DATA_SUB_ID] = subId }
                }
            if (result == null) {
                AppLogger.w("CallSessionRepositoryImpl: setLastDataSubId timed out")
            }
        }

        /**
         * Atomically saves all call session data for the given subId.
         *
         * @throws IOException if DataStore write fails.
         */
        override suspend fun saveCallSession(
            subId: Int,
            startTime: Long,
            bootTime: Long,
            uptime: Long,
            identity: String?,
        ) {
            val result =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
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
            if (result == null) {
                AppLogger.w("CallSessionRepositoryImpl: saveCallSession timed out for subId $subId")
            }
        }

        /**
         * Atomically clears all call session keys for the given subId.
         *
         * @throws IOException if DataStore write fails.
         */
        override suspend fun clearCallSession(subId: Int) {
            val result =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
                    dataStore.edit { prefs ->
                        prefs.remove(DataStoreKeys.callStartTimeKey(subId))
                        prefs.remove(DataStoreKeys.bootTimeKey(subId))
                        prefs.remove(DataStoreKeys.uptimeKey(subId))
                        prefs.remove(DataStoreKeys.callIdentityKey(subId))
                    }
                }
            if (result == null) {
                AppLogger.w("CallSessionRepositoryImpl: clearCallSession timed out for subId $subId")
            }
        }

        /**
         * Returns the call start time for the given subId.
         *
         * Safe to call from any coroutine context.
         */
        override fun getCallStartTime(subId: Int): Flow<Long> = getData(DataStoreKeys.callStartTimeKey(subId), 0L)

        /**
         * Returns the device boot time recorded when the call started on the given subId.
         *
         * Safe to call from any coroutine context.
         */
        override fun getBootTime(subId: Int): Flow<Long> = getData(DataStoreKeys.bootTimeKey(subId), 0L)

        /**
         * Returns the device uptime recorded when the call started on the given subId.
         *
         * Safe to call from any coroutine context.
         */
        override fun getUptime(subId: Int): Flow<Long> = getData(DataStoreKeys.uptimeKey(subId), 0L)

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
         * @throws IOException if DataStore write fails (thrown from dataStore.edit).
         */
        override suspend fun clearAllCallSessions() {
            val result =
                withTimeoutOrNull(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds) {
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
            if (result == null) {
                AppLogger.w("CallSessionRepositoryImpl: clearAllCallSessions timed out")
            }
        }

        /**
         * Returns a list of subscription IDs that currently have active call sessions.
         *
         * Safe to call from any coroutine context.
         * @return List of active subIds, or null if reading timed out.
         */
        override suspend fun getActiveCallSubIds(): List<Int>? {
            val prefs =
                dataStore.data.firstWithTimeoutOrLog(
                    timeoutMillis = Constants.Flow.SHORT_TIMEOUT_MS,
                    tag = "CallSessionRepositoryImpl",
                ) ?: return null
            return prefs.asMap().entries.mapNotNull { (key, value) ->
                if ((key.name.startsWith(DataStoreKeys.CALL_START_TIME_PREFIX)) && (((value as? Long) ?: 0L) > 0L)) {
                    key.name.removePrefix(DataStoreKeys.CALL_START_TIME_PREFIX).toIntOrNull()
                } else {
                    null
                }
            }
        }
    }
