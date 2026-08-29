package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.data.di.DataStoreKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimWidgetVisibilityRepositoryImplTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SimWidgetVisibilityRepositoryImpl

    @Before
    fun setup() {
        dataStore =
            PreferenceDataStoreFactory.create {
                tmpFolder.newFile("test_sim_widget.preferences_pb")
            }
        repository = SimWidgetVisibilityRepositoryImpl(dataStore)
    }

    @Test
    fun `clearOrphanedSettings should NOT remove keys for active multi-segment identities`() =
        runTest {
            val identity = "icc_abc123"
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.showSimKey(identity)] = true
                prefs[DataStoreKeys.showSimIconKey(identity)] = false
            }

            repository.clearOrphanedSettings(listOf(identity))

            val prefs = dataStore.data.first()
            assertNotNull(prefs[DataStoreKeys.showSimKey(identity)])
            assertNotNull(prefs[DataStoreKeys.showSimIconKey(identity)])
        }

    @Test
    fun `clearOrphanedSettings should NEVER remove fallback keys`() =
        runTest {
            val fallbackIdentity = "sim_0"
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.showSimKey(fallbackIdentity)] = true
            }

            // sim_0 is NOT in activeIdentities
            repository.clearOrphanedSettings(emptyList())

            val prefs = dataStore.data.first()
            assertNotNull(prefs[DataStoreKeys.showSimKey(fallbackIdentity)])
        }

    @Test
    fun `clearOrphanedSettings should remove keys for missing identities`() =
        runTest {
            val oldIdentity = "icc_old_gone"
            val activeIdentity = "icc_active"

            dataStore.edit { prefs ->
                prefs[DataStoreKeys.showSimKey(oldIdentity)] = true
                prefs[DataStoreKeys.showSimKey(activeIdentity)] = true
            }

            repository.clearOrphanedSettings(listOf(activeIdentity))

            val prefs = dataStore.data.first()
            assertNull(prefs[DataStoreKeys.showSimKey(oldIdentity)])
            assertNotNull(prefs[DataStoreKeys.showSimKey(activeIdentity)])
        }

    @Test
    fun `clearOrphanedSettings should handle all widget prefixes correctly`() =
        runTest {
            val identity = "icc_test"
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.showSimKey(identity)] = true
                prefs[DataStoreKeys.showSimIconKey(identity)] = true
                prefs[DataStoreKeys.showSimNameKey(identity)] = true
                prefs[DataStoreKeys.showMinutesKey(identity)] = true
                prefs[DataStoreKeys.showMinutesLimitKey(identity)] = true
                prefs[DataStoreKeys.showTrafficKey(identity)] = true
                prefs[DataStoreKeys.showTrafficLimitKey(identity)] = true
            }

            // Remove it
            repository.clearOrphanedSettings(emptyList())

            val prefs = dataStore.data.first()
            assertNull(prefs[DataStoreKeys.showSimKey(identity)])
            assertNull(prefs[DataStoreKeys.showSimIconKey(identity)])
            assertNull(prefs[DataStoreKeys.showSimNameKey(identity)])
            assertNull(prefs[DataStoreKeys.showMinutesKey(identity)])
            assertNull(prefs[DataStoreKeys.showMinutesLimitKey(identity)])
            assertNull(prefs[DataStoreKeys.showTrafficKey(identity)])
            assertNull(prefs[DataStoreKeys.showTrafficLimitKey(identity)])
        }

    @Test
    fun `getSimSettingsBatch returns defaults for missing keys`() =
        runTest {
            val identity = "icc_new"
            val settings = repository.getSimSettingsBatch(listOf(identity)).first()

            val result = settings[identity]
            assertNotNull(result)
            assertEquals(true, result?.showSim)
            assertEquals(true, result?.showIcon)
            assertEquals(true, result?.showName)
            assertEquals(true, result?.showMinutes)
            assertEquals(false, result?.showMinutesLimit)
            assertEquals(true, result?.showTraffic)
            assertEquals(false, result?.showTrafficLimit)
        }
}
