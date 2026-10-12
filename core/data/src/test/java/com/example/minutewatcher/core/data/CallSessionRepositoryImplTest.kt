package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        dataStore = InMemoryPreferencesDataStore()
    }

    @Test
    fun `clear all call sessions removes expected keys`() =
        runTest {
            val repository = CallSessionRepositoryImpl(dataStore)

            dataStore.edit { prefs ->
                prefs[DataStoreKeys.callStartTimeKey(1)] = 1000L
                prefs[DataStoreKeys.bootTimeKey(1)] = 5000L
                prefs[DataStoreKeys.callIdentityKey(1)] = "id1"

                prefs[DataStoreKeys.callStartTimeKey(2)] = 2000L
                prefs[DataStoreKeys.bootTimeKey(2)] = 6000L

                prefs[DataStoreKeys.APP_THEME] = AppTheme.DARK.value
            }

            repository.clearAllCallSessions()
            advanceUntilIdle()

            val prefs = dataStore.data.first()
            assertNull(prefs[DataStoreKeys.callStartTimeKey(1)])
            assertNull(prefs[DataStoreKeys.bootTimeKey(1)])
            assertNull(prefs[DataStoreKeys.callIdentityKey(1)])
            assertNull(prefs[DataStoreKeys.callStartTimeKey(2)])
            assertNull(prefs[DataStoreKeys.bootTimeKey(2)])

            assertEquals(AppTheme.DARK.value, prefs[DataStoreKeys.APP_THEME])
        }

    @Test
    fun `get active call subIds`() =
        runTest {
            val repository = CallSessionRepositoryImpl(dataStore)

            dataStore.edit { prefs ->
                prefs[DataStoreKeys.callStartTimeKey(1)] = 1000L
                prefs[DataStoreKeys.callStartTimeKey(3)] = 3000L
                prefs[DataStoreKeys.callStartTimeKey(5)] = 0L
            }

            val activeSubIds = repository.getActiveCallSubIds()
            assertEquals(setOf(1, 3), activeSubIds?.toSet())
        }

    @Test
    fun `save call session writes all fields atomically`() =
        runTest {
            val repository = CallSessionRepositoryImpl(dataStore)
            val subId = 1
            val startTime = 1000L
            val elapsedRealtime = 2000L
            val uptime = 3000L
            val identity = "test_identity"

            repository.saveCallSession(subId, startTime, elapsedRealtime, uptime, identity)
            advanceUntilIdle()

            val prefs = dataStore.data.first()
            assertEquals(startTime, prefs[DataStoreKeys.callStartTimeKey(subId)])
            assertEquals(elapsedRealtime, prefs[DataStoreKeys.bootTimeKey(subId)])
            assertEquals(uptime, prefs[DataStoreKeys.uptimeKey(subId)])
            assertEquals(identity, prefs[DataStoreKeys.callIdentityKey(subId)])
        }

    @Test
    fun `clear call session removes all fields atomically`() =
        runTest {
            val repository = CallSessionRepositoryImpl(dataStore)
            val subId = 1

            // 1. Setup session
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.callStartTimeKey(subId)] = 1000L
                prefs[DataStoreKeys.bootTimeKey(subId)] = 2000L
                prefs[DataStoreKeys.uptimeKey(subId)] = 3000L
                prefs[DataStoreKeys.callIdentityKey(subId)] = "test_id"
            }

            // 2. Clear
            repository.clearCallSession(subId)
            advanceUntilIdle()

            // 3. Verify
            val prefs = dataStore.data.first()
            assertNull(prefs[DataStoreKeys.callStartTimeKey(subId)])
            assertNull(prefs[DataStoreKeys.bootTimeKey(subId)])
            assertNull(prefs[DataStoreKeys.uptimeKey(subId)])
            assertNull(prefs[DataStoreKeys.callIdentityKey(subId)])
        }
}
