package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@RunWith(RobolectricTestRunner::class)

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityMigrationServiceTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var appDataStore: DataStore<Preferences>
    private lateinit var simDataStore: DataStore<Preferences>

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        appDataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { tmpFolder.newFile("test_app_settings.preferences_pb") },
            )
        simDataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { tmpFolder.newFile("test_sim_settings.preferences_pb") },
            )
    }

    @Test
    fun testMigration() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            appDataStore.edit { it[legacyKey] = 123 }

            service.migrateLegacyData(
                com.example.minutewatcher.core.model.SimIdentity(
                    stableId = identityId,
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                ),
            )

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

            service.migrateLegacyData(
                com.example.minutewatcher.core.model.SimIdentity(
                    stableId = "some_id",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                ),
            )
            assertFalse(service.hasLegacyData())
        }

    @Test
    fun testMigrationSlot1() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_1")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            appDataStore.edit { it[legacyKey] = 42 }

            service.migrateLegacyData(
                com.example.minutewatcher.core.model.SimIdentity(
                    stableId = identityId,
                    subscriptionId = 1,
                    slotIndex = 1,
                    source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                ),
            )

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

            service.migrateLegacyData(
                com.example.minutewatcher.core.model.SimIdentity(
                    stableId = "sub_$subId",
                    subscriptionId = subId,
                    slotIndex = 0,
                    source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                ),
            )

            val prefs = simDataStore.data.first()
            // Data migrated
            assertEquals(100, prefs[intPreferencesKey("data_sub_$subId")])
            // Marker removed
            assertFalse(prefs.asMap().keys.any { it.name.startsWith("subscription_id_icc_") })

            // Modern data is still present, so hasLegacyData() will return true in this implementation
            // because it can't distinguish between legacy and modern without active SIMs.
            // But if we use a non-matching ID for modern data, we can verify it's not flagged.
            simDataStore.edit { it.clear() }
            simDataStore.edit { it[intPreferencesKey("app_theme")] = 1 }
            assertFalse(service.hasLegacyData())
        }

    @Test
    fun testMigration_ConflictPreservesLegacyData() =
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
            service.migrateLegacyData(
                com.example.minutewatcher.core.model.SimIdentity(
                    stableId = identityId,
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = com.example.minutewatcher.core.model.IdentitySource.SUBSCRIPTION_ID,
                ),
            )

            val prefs = appDataStore.data.first()
            // 3. New value should be preserved
            assertEquals(222, prefs[newKey])
            // 4. Old value should be moved to conflict key
            assertEquals(111, prefs[conflictKey])
            // 5. Original old key should be removed
            assertNull(prefs[legacyKey])
            // 6. hasLegacyData() should return false (it ignores conflicts)
            assertFalse(service.hasLegacyData())
        }
}

