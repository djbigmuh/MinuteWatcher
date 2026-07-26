package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
            val identity = "new_identity"
            val newKey = intPreferencesKey("test_key_$identity")

            appDataStore.edit { it[legacyKey] = 123 }

            service.migrateLegacyData(0, identity)

            val prefs = appDataStore.data.first()
            assertEquals(123, prefs[newKey])
            assertEquals(null, prefs[legacyKey])
        }

    @Test
    fun testHasLegacyData() = testScope.runTest {
        val service = IdentityMigrationService(appDataStore, simDataStore)
        
        assertFalse(service.hasLegacyData())
        
        appDataStore.edit { it[intPreferencesKey("some_key_sim_0")] = 1 }
        assertTrue(service.hasLegacyData())
        
        service.migrateLegacyData(0, "some_id")
        assertFalse(service.hasLegacyData())
    }
}
