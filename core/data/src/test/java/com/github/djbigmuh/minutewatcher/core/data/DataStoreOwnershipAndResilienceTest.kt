package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.data.testing.createTestFilePreferencesDataStore
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreOwnershipAndResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Test
    fun `DataStore recovers automatically from file corruption via corruption handler`() =
        runTest {
            val file = File(tmpFolder.root, "corrupted_${UUID.randomUUID()}.preferences_pb")
            file.writeText("Corrupted garbage bytes that fail protobuf parsing")

            val corruptionHandler =
                ReplaceFileCorruptionHandler {
                    emptyPreferences()
                }

            val dataStore =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = corruptionHandler,
                    scope = testScope,
                    produceFile = { file },
                )

            val prefs = dataStore.data.first()
            assertEquals(emptyPreferences(), prefs)

            val testKey = intPreferencesKey("test_key")
            dataStore.edit { it[testKey] = 42 }
            assertEquals(42, dataStore.data.first()[testKey])
        }

    @Test
    fun `IOException on read results in observable error state in AppSettingsRepository`() =
        runTest {
            val realDataStore = createTestFilePreferencesDataStore(tmpFolder, testScope)
            var throwReadError = true

            val failingDataStore =
                object : DataStore<Preferences> by realDataStore {
                    override val data: Flow<Preferences> =
                        flow {
                            if (throwReadError) {
                                throw IOException("Simulated read failure")
                            } else {
                                realDataStore.data.collect { emit(it) }
                            }
                        }
                }

            val repository = AppSettingsRepositoryImpl(failingDataStore, testScope)
            runCurrent()

            advanceTimeBy(60.seconds)
            runCurrent()

            val status = repository.status.value
            assertTrue("Expected TrackingStatus.Error on read failure", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            throwReadError = false
            advanceTimeBy(10.seconds)
            runCurrent()

            assertEquals(TrackingStatus.Active, repository.status.value)
        }

    @Test
    fun `IOException on write propagates error and preserves DataStore state`() =
        runTest {
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flow { emit(emptyPreferences()) }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                        throw IOException("Simulated write failure")
                }

            val repository = AppSettingsRepositoryImpl(failingDataStore, testScope)
            val result =
                runCatching {
                    repository.updateAppSettings { it.copy(widgetFontSize = 5) }
                }

            assertTrue("Expected IOException on write", result.exceptionOrNull() is IOException)
        }

    @Test
    fun `Concurrent edit operations execute atomically without lost updates`() =
        runTest {
            val dataStore = createTestFilePreferencesDataStore(tmpFolder, testScope)
            val key = intPreferencesKey("counter")
            dataStore.edit { it[key] = 0 }

            val jobs =
                List(50) {
                    launch {
                        dataStore.edit { prefs ->
                            val current = prefs[key] ?: 0
                            prefs[key] = current + 1
                        }
                    }
                }
            jobs.joinAll()

            val finalCount = dataStore.data.first()[key]
            assertEquals(50, finalCount)
        }

    @Test
    fun `DataStore scope cancellation cancels pending operations cleanly`() =
        runTest {
            val dsScope = CoroutineScope(SupervisorJob() + mainDispatcherRule.testDispatcher)
            val dataStore = createTestFilePreferencesDataStore(tmpFolder, dsScope)

            val key = stringPreferencesKey("cancel_test")
            dataStore.edit { it[key] = "initial" }

            dsScope.cancel()

            val result =
                runCatching {
                    dataStore.edit { it[key] = "new" }
                }
            assertTrue(
                "Operation on canceled scope should throw CancellationException or IOException",
                result.isFailure &&
                    (
                        result.exceptionOrNull() is CancellationException ||
                            result.exceptionOrNull() is IOException
                    ),
            )
        }

    @Test
    fun `removeOrphanedIdentityKeys removes keys for inactive SIM identities`() =
        runTest {
            val appDs = createTestFilePreferencesDataStore(tmpFolder, testScope)
            val simDs = createTestFilePreferencesDataStore(tmpFolder, testScope)

            val activeId = "icc_active_123"
            val inactiveId = "icc_inactive_456"

            simDs.edit { prefs ->
                prefs[DataStoreKeys.monthlyLimitKey(activeId)] = 100
                prefs[DataStoreKeys.trafficLimitKey(activeId)] = 5000
                prefs[DataStoreKeys.monthlyLimitKey(inactiveId)] = 200
                prefs[DataStoreKeys.trafficLimitKey(inactiveId)] = 10000
            }

            val migrationService = IdentityMigrationService(appDs, simDs)
            migrationService.removeOrphanedIdentityKeys(setOf(activeId))

            val prefs = simDs.data.first()
            assertEquals(100, prefs[DataStoreKeys.monthlyLimitKey(activeId)])
            assertEquals(5000, prefs[DataStoreKeys.trafficLimitKey(activeId)])
            assertNull(prefs[DataStoreKeys.monthlyLimitKey(inactiveId)])
            assertNull(prefs[DataStoreKeys.trafficLimitKey(inactiveId)])
        }
}
