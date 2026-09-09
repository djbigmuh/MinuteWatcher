package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.util.SimIdentityUtils
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    private lateinit var appDataStore: DataStore<Preferences>
    private lateinit var simDataStore: DataStore<Preferences>

    @Before
    fun setup() {
        appDataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
            ) { tmpFolder.newFile("test_app_settings.preferences_pb") }
        simDataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
            ) { tmpFolder.newFile("test_sim_settings.preferences_pb") }
    }

    @Test
    fun `concurrent settings updates`() =
        runTest {
            val repository = AppSettingsRepositoryImpl(appDataStore, testScope)
            val updateCount = 5
            val jobs =
                List(updateCount) {
                    launch {
                        repository.updateAppSettings { current ->
                            current.copy(
                                inaccurateCallAttributionsCount = current.inaccurateCallAttributionsCount + 1,
                            )
                        }
                    }
                }

            jobs.joinAll()
            advanceUntilIdle()

            val finalSettings = repository.getAppSettings().first()
            assertEquals(updateCount, finalSettings.inaccurateCallAttributionsCount)
        }

    @Test
    fun `idempotent migration`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identity =
                SimIdentity(
                    stableId = "new_identity",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.SUBSCRIPTION_ID,
                    isStable = false,
                )

            // Setup legacy data
            appDataStore.edit { it[legacyKey] = 123 }
            advanceUntilIdle()

            // First run
            val outcome1 = service.migrateLegacyData(identity)
            advanceUntilIdle()
            assertTrue("First migration should have changes", outcome1.hasChanges())
            assertEquals(1, outcome1.migrated)

            // Second run
            val outcome2 = service.migrateLegacyData(identity)
            advanceUntilIdle()
            assertFalse("Second migration should be a no-op", outcome2.hasChanges())
            assertEquals(0, outcome2.migrated)

            val prefs = appDataStore.data.first()
            assertEquals(123, prefs[intPreferencesKey("test_key_new_identity")])
            assertNull(prefs[legacyKey])
        }

    @Test
    fun `partial migration failure recovery`() =
        runTest {
            var shouldFail = true
            val testAppDataStore =
                object : DataStore<Preferences> {
                    override val data = appDataStore.data

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (shouldFail) {
                            shouldFail = false
                            throw IOException("Simulated disk failure")
                        }
                        return appDataStore.updateData(transform)
                    }
                }

            val service = IdentityMigrationService(testAppDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identity =
                SimIdentity(
                    stableId = "new_identity",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.SUBSCRIPTION_ID,
                    isStable = false,
                )

            // Setup legacy data in BOTH stores
            appDataStore.edit { it[legacyKey] = 100 }
            simDataStore.edit { it[legacyKey] = 200 }
            advanceUntilIdle()

            // First run - AppDataStore fails, SimDataStore succeeds
            val outcome1 = service.migrateLegacyData(identity)
            advanceUntilIdle()

            assertTrue(outcome1.partialFailure)
            assertEquals(1, outcome1.migrated) // Migrated from SimDataStore

            // Verify SimDataStore migrated, AppDataStore didn't
            val simPrefs = simDataStore.data.first()
            assertEquals(200, simPrefs[intPreferencesKey("test_key_new_identity")])
            assertNull(simPrefs[legacyKey])

            val appPrefsBefore = appDataStore.data.first()
            assertEquals(100, appPrefsBefore[legacyKey])
            assertNull(appPrefsBefore[intPreferencesKey("test_key_new_identity")])

            // Second run - AppDataStore succeeds now
            val outcome2 = service.migrateLegacyData(identity)
            advanceUntilIdle()

            assertFalse("Second run should not have partial failure", outcome2.partialFailure)
            assertEquals(1, outcome2.migrated) // Migrated from AppDataStore

            // Verify everything migrated
            val appPrefsAfter = appDataStore.data.first()
            assertEquals(100, appPrefsAfter[intPreferencesKey("test_key_new_identity")])
            assertNull(appPrefsAfter[legacyKey])
        }

    @Test
    fun `ephemeral migration idempotency`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val ephemeralSeed = "ephemeral_seed"

            val simInfo =
                ActiveSimInfo(
                    identity =
                        SimIdentity(
                            stableId = "icc_persistent_hash", // simplified for test
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.ICCID,
                            isStable = true,
                        ),
                    subscriptionId = 1,
                    slotIndex = 0,
                    iccId = "icc_123",
                    displayName = "SIM 1",
                    carrierName = "Carrier",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )

            // Use the utility to get the actual ephemeral identity string for the test
            val ephemeralId = SimIdentityUtils.createIdentityFromInfo(simInfo, ephemeralSeed).stableId
            val legacyKey = intPreferencesKey("test_key_$ephemeralId")

            appDataStore.edit { it[legacyKey] = 555 }
            advanceUntilIdle()

            // First run
            val outcome1 = service.migrateFromEphemeralSeed(ephemeralSeed, listOf(simInfo))
            advanceUntilIdle()
            assertTrue("First migration should have changes", outcome1.hasChanges())

            // Second run
            val outcome2 = service.migrateFromEphemeralSeed(ephemeralSeed, listOf(simInfo))
            advanceUntilIdle()
            assertFalse("Second migration should be a no-op", outcome2.hasChanges())

            val prefs = appDataStore.data.first()
            // Should be migrated to the identity in simInfo
            assertEquals(555, prefs[intPreferencesKey("test_key_icc_persistent_hash")])
            assertNull(prefs[legacyKey])
        }
}
