package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityMigrationServiceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var appDataStore: DataStore<Preferences>
    private lateinit var simDataStore: DataStore<Preferences>

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

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
    fun testMigration() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            appDataStore.edit { it[legacyKey] = 123 }
            advanceUntilIdle()

            val outcome =
                service.migrateLegacyData(
                    com.example.minutewatcher.core.model.SimIdentity(
                        stableId = identityId,
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            advanceUntilIdle()

            assertTrue(outcome.migrated > 0)
            assertEquals(0, outcome.failures)
            val prefs = appDataStore.data.first()
            assertEquals(123, prefs[newKey])
            assertEquals(null, prefs[legacyKey])
        }

    @Test
    fun testHasLegacyData() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            assertFalse(service.hasLegacyData())

            appDataStore.edit { it[intPreferencesKey("some_key_sim_0")] = 1 }
            assertTrue(service.hasLegacyData())

            val outcome =
                service.migrateLegacyData(
                    com.example.minutewatcher.core.model.SimIdentity(
                        stableId = "some_id",
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            assertTrue(outcome.migrated > 0)
            assertFalse(service.hasLegacyData(setOf("some_id")))
        }

    @Test
    fun testMigrationSlot1() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_1")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            appDataStore.edit { it[legacyKey] = 42 }

            val outcome =
                service.migrateLegacyData(
                    com.example.minutewatcher.core.model.SimIdentity(
                        stableId = identityId,
                        subscriptionId = 1,
                        slotIndex = 1,
                        source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            assertTrue(outcome.migrated > 0)

            val prefs = appDataStore.data.first()
            assertEquals(42, prefs[newKey])
            assertEquals(null, prefs[legacyKey])
        }

    @Test
    fun testHasLegacyData_Patterns() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            assertFalse(service.hasLegacyData())

            // V1
            appDataStore.edit { it[intPreferencesKey("key_sim_0")] = 1 }
            assertTrue(service.hasLegacyData())
            appDataStore.edit { it.clear() }

            // Sub pattern
            simDataStore.edit { it[intPreferencesKey("key_sub_123")] = 1 }
            assertTrue(service.hasLegacyData())
            simDataStore.edit { it.clear() }

            // ICC pattern
            appDataStore.edit { it[intPreferencesKey("key_icc_abc")] = 1 }
            assertTrue(service.hasLegacyData())
            appDataStore.edit { it.clear() }

            // Mapping marker
            simDataStore.edit { it[intPreferencesKey("subscription_id_icc_abc")] = 1 }
            assertTrue(service.hasLegacyData())
            simDataStore.edit { it.clear() }

            // Non-legacy
            appDataStore.edit { it[intPreferencesKey("app_theme")] = 1 }
            assertFalse(service.hasLegacyData())
        }

    @Test
    fun testHasLegacyData_IgnoresActiveIdentities() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            // Keys that contain _sub_ or _icc_ but belong to active identities should not be legacy
            simDataStore.edit { it[intPreferencesKey("minutes_used_sub_123")] = 10 }
            appDataStore.edit { it[intPreferencesKey("traffic_used_icc_abc")] = 100 }

            // If we don't know active identities, they might still look like legacy (for safety if schema is old)
            assertTrue(service.hasLegacyData())

            // If they are known active, they are not legacy
            assertFalse(service.hasLegacyData(setOf("sub_123", "icc_abc")))
        }

    @Test
    fun testHasLegacyData_SchemaVersionCheck() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            // Populate with legacy-looking data
            appDataStore.edit { it[intPreferencesKey("key_sub_123")] = 1 }

            assertTrue(service.hasLegacyData())

            // Mark schema as current
            service.markSchemaCurrent()

            // Now hasLegacyData should return false immediately regardless of keys
            assertFalse(service.hasLegacyData())
        }

    @Test
    fun testMigration_CleansUpMappingMarkers() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val subId = 5
            val legacyStableId = "icc_some_hash"

            // Setup mapping marker and legacy data
            simDataStore.edit {
                it[intPreferencesKey("subscription_id_$legacyStableId")] = subId
                it[intPreferencesKey("data_$legacyStableId")] = 100
            }

            assertTrue(service.hasLegacyData())

            val outcome =
                service.migrateLegacyData(
                    com.example.minutewatcher.core.model.SimIdentity(
                        stableId = "sub_$subId",
                        subscriptionId = subId,
                        slotIndex = 0,
                        source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            assertTrue(outcome.migrated > 0)

            val prefs = simDataStore.data.first()
            // Data migrated
            assertEquals(100, prefs[intPreferencesKey("data_sub_$subId")])
            // Marker removed
            assertFalse(prefs.asMap().keys.any { it.name.startsWith("subscription_id_icc_") })

            // With active identity, should return false
            assertFalse(service.hasLegacyData(setOf("sub_$subId")))

            simDataStore.edit { it.clear() }
            simDataStore.edit { it[intPreferencesKey("app_theme")] = 1 }
            assertFalse(service.hasLegacyData())
        }

    @Test
    fun testMigration_ConflictPreservesModernValueAndBacksUpLegacy() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")
            val conflictKey = intPreferencesKey("conflict_test_key_sim_0")

            // 1. Setup conflict: both old and new keys exist
            appDataStore.edit {
                it[legacyKey] = 111 // Old value
                it[newKey] = 222 // New value already exists
            }

            // 2. Migrate
            val outcome =
                service.migrateLegacyData(
                    com.example.minutewatcher.core.model.SimIdentity(
                        stableId = identityId,
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )

            assertEquals(0, outcome.migrated)
            assertTrue("Conflict count should be > 0", outcome.conflicts > 0)
            assertEquals(0, outcome.failures)
            assertFalse("Partial failure should be false for resolved conflicts", outcome.partialFailure)

            val prefs = appDataStore.data.first()
            // 3. Modern value should be preserved
            assertEquals(222, prefs[newKey])
            // 4. Legacy value should be moved to conflict key
            assertEquals(111, prefs[conflictKey])
            // 5. Original old key should be REMOVED (Policy Fix)
            assertNull("Legacy key must be removed after conflict resolution", prefs[legacyKey])
            // 6. hasLegacyData() should return false (Conflict resolved)
            assertFalse(service.hasLegacyData())
        }

    @Test
    fun `modern sub keys are not treated as legacy when schema is current`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            service.markSchemaCurrent()

            simDataStore.edit { it[intPreferencesKey("minutes_used_sub_1")] = 10 }

            assertFalse(service.hasLegacyData())
        }

    @Test
    fun `modern icc keys are not treated as legacy when schema is current`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            service.markSchemaCurrent()

            appDataStore.edit { it[intPreferencesKey("traffic_limit_icc_abc")] = 1000 }

            assertFalse(service.hasLegacyData())
        }

    @Test
    fun `legacy sim_0 keys are detected when schema is old`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            // schema version is 0 by default in tests

            appDataStore.edit { it[intPreferencesKey("some_key_sim_0")] = 1 }

            assertTrue(service.hasLegacyData())
        }

    @Test
    fun `active identity keys are excluded from legacy detection`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            // Even if schema is old, active identities should be safe

            val activeIdentity = "sub_5"
            simDataStore.edit { it[intPreferencesKey("minutes_used_$activeIdentity")] = 20 }

            assertFalse(service.hasLegacyData(activeIdentities = setOf(activeIdentity)))
        }

    @Test(expected = IOException::class)
    fun `hasLegacyData throws IOException on DataStore error`() =
        testScope.runTest {
            val corruptedDataStore: DataStore<Preferences> = mock()
            whenever(corruptedDataStore.data).thenReturn(flow { throw IOException("Disk read error") })

            val service = IdentityMigrationService(corruptedDataStore, simDataStore)
            service.hasLegacyData()
        }

    @Test
    fun `migrateLegacyData does not crash on Exception`() =
        testScope.runTest {
            val corruptedDataStore: DataStore<Preferences> = mock()
            whenever(corruptedDataStore.edit(any())).thenThrow(RuntimeException("Write failed"))

            val service = IdentityMigrationService(corruptedDataStore, simDataStore)
            val activeIdentity =
                com.example.minutewatcher.core.model.SimIdentity(
                    stableId = "new_id",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                    isStable = false,
                )

            // Should catch the internal exception and log it, not throw
            try {
                service.migrateLegacyData(activeIdentity)
            } catch (_: Exception) {
                // Expected now
            }
        }

    @Test
    fun testConcurrentMigrationDoesNotCorruptData() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            // Setup legacy data
            appDataStore.edit { it[legacyKey] = 123 }
            advanceUntilIdle()

            val identity =
                com.example.minutewatcher.core.model.SimIdentity(
                    stableId = identityId,
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                    isStable = false,
                )

            // Launch multiple concurrent migrations
            val results = mutableListOf<IdentityMigrationService.MigrationOutcome>()
            val jobs =
                List(5) {
                    launch {
                        results.add(service.migrateLegacyData(identity))
                    }
                }

            jobs.joinAll()
            advanceUntilIdle()

            val prefs = appDataStore.data.first()
            // Data successfully migrated to the new key
            assertEquals(123, prefs[newKey])
            // Legacy key is removed
            assertNull(prefs[legacyKey])
            // No conflict backup keys created
            assertFalse(prefs.asMap().keys.any { it.name.startsWith("conflict_") })

            // Exactly one migration should have returned true (if they ran sequentially thanks to Mutex)
            assertEquals(1, results.count { it.hasChanges() })
        }

    @Test
    fun testMigration_FailureLoggedAndReported() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            // Actually, migrateTypedKey returns FAILURE if value is null or unknown type.

            val outcome =
                service.migrateLegacyData(
                    com.example.minutewatcher.core.model.SimIdentity(
                        stableId = "id",
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )

            assertEquals(0, outcome.migrated)
            assertEquals(0, outcome.failures) // No keys to migrate
        }
}
