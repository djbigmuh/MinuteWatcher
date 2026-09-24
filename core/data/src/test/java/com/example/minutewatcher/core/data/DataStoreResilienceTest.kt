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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun createAppDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val file = tmpFolder.newFile("test_app_settings_${UUID.randomUUID()}.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    private fun createSimDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val file = tmpFolder.newFile("test_sim_settings_${UUID.randomUUID()}.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Test
    fun `concurrent settings updates`() =
        runTest {
            val dataStore = createAppDataStore(backgroundScope)
            val repository = AppSettingsRepositoryImpl(dataStore, backgroundScope)
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

            withTimeout(30.seconds) { jobs.joinAll() }
            runCurrent()

            val finalSettings = withTimeout(10.seconds) { repository.getAppSettings().first() }
            assertEquals(updateCount, finalSettings.inaccurateCallAttributionsCount)
        }

    @Test
    fun `idempotent migration`() =
        runTest {
            val appStore = createAppDataStore(backgroundScope)
            val simStore = createSimDataStore(backgroundScope)
            val service = IdentityMigrationService(appStore, simStore)
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
            appStore.edit { it[legacyKey] = 123 }
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

            val prefs = appStore.data.first()
            assertEquals(123, prefs[intPreferencesKey("test_key_new_identity")])
            assertNull(prefs[legacyKey])
        }

    @Test
    fun `partial migration failure recovery`() =
        runTest {
            val appStore = createAppDataStore(backgroundScope)
            val simStore = createSimDataStore(backgroundScope)
            var shouldFail = true
            val testAppDataStore =
                object : DataStore<Preferences> {
                    override val data = appStore.data

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (shouldFail) {
                            shouldFail = false
                            throw IOException("Simulated disk failure")
                        }
                        return appStore.updateData(transform)
                    }
                }

            val service = IdentityMigrationService(testAppDataStore, simStore)
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
            appStore.edit { it[legacyKey] = 100 }
            simStore.edit { it[legacyKey] = 200 }
            advanceUntilIdle()

            // First run - AppDataStore fails, SimDataStore succeeds
            val outcome1 = service.migrateLegacyData(identity)
            advanceUntilIdle()

            assertTrue(outcome1.partialFailure)
            assertEquals(1, outcome1.migrated) // Migrated from SimDataStore

            // Verify SimDataStore migrated, AppDataStore didn't
            val simPrefs = simStore.data.first()
            assertEquals(200, simPrefs[intPreferencesKey("test_key_new_identity")])
            assertNull(simPrefs[legacyKey])

            val appPrefsBefore = appStore.data.first()
            assertEquals(100, appPrefsBefore[legacyKey])
            assertNull(appPrefsBefore[intPreferencesKey("test_key_new_identity")])

            // Second run - AppDataStore succeeds now
            val outcome2 = service.migrateLegacyData(identity)
            advanceUntilIdle()

            assertFalse("Second run should not have partial failure", outcome2.partialFailure)
            assertEquals(1, outcome2.migrated) // Migrated from AppDataStore

            // Verify everything migrated
            val appPrefsAfter = appStore.data.first()
            assertEquals(100, appPrefsAfter[intPreferencesKey("test_key_new_identity")])
            assertNull(appPrefsAfter[legacyKey])
        }

    @Test
    fun `ephemeral migration idempotency`() =
        runTest {
            val appStore = createAppDataStore(backgroundScope)
            val simStore = createSimDataStore(backgroundScope)
            val service = IdentityMigrationService(appStore, simStore)
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

            appStore.edit { it[legacyKey] = 555 }
            advanceUntilIdle()

            // First run
            val outcome1 = service.migrateFromEphemeralSeed(ephemeralSeed, listOf(simInfo))
            advanceUntilIdle()
            assertTrue("First migration should have changes", outcome1.hasChanges())

            // Second run
            val outcome2 = service.migrateFromEphemeralSeed(ephemeralSeed, listOf(simInfo))
            advanceUntilIdle()
            assertFalse("Second migration should be a no-op", outcome2.hasChanges())

            val prefs = appStore.data.first()
            // Should be migrated to the identity in simInfo
            assertEquals(555, prefs[intPreferencesKey("test_key_icc_persistent_hash")])
            assertNull(prefs[legacyKey])
        }
}
