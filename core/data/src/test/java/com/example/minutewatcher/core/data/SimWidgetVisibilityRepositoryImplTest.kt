package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
            PreferenceDataStoreFactory.create(
                produceFile = { tmpFolder.newFile("test_sim_widget.preferences_pb") },
            )
        repository = SimWidgetVisibilityRepositoryImpl(dataStore)
    }

    @Test
    fun `clearOrphanedSettings should NOT remove keys for active multi-segment identities`() =
        runTest {
            val identity = "icc_abc123"
            dataStore.edit { prefs ->
                prefs[WidgetSettingsKeys.showSimKey(identity)] = true
                prefs[WidgetSettingsKeys.showSimIconKey(identity)] = false
            }

            repository.clearOrphanedSettings(listOf(identity))

            val prefs = dataStore.data.first()
            assertNotNull(prefs[WidgetSettingsKeys.showSimKey(identity)])
            assertNotNull(prefs[WidgetSettingsKeys.showSimIconKey(identity)])
        }

    @Test
    fun `clearOrphanedSettings should NEVER remove fallback keys`() =
        runTest {
            val fallbackIdentity = "sim_0"
            dataStore.edit { prefs ->
                prefs[WidgetSettingsKeys.showSimKey(fallbackIdentity)] = true
            }

            // sim_0 is NOT in activeIdentities
            repository.clearOrphanedSettings(emptyList())

            val prefs = dataStore.data.first()
            assertNotNull(prefs[WidgetSettingsKeys.showSimKey(fallbackIdentity)])
        }

    @Test
    fun `clearOrphanedSettings should remove keys for missing identities`() =
        runTest {
            val oldIdentity = "icc_old_gone"
            val activeIdentity = "icc_active"

            dataStore.edit { prefs ->
                prefs[WidgetSettingsKeys.showSimKey(oldIdentity)] = true
                prefs[WidgetSettingsKeys.showSimKey(activeIdentity)] = true
            }

            repository.clearOrphanedSettings(listOf(activeIdentity))

            val prefs = dataStore.data.first()
            assertNull(prefs[WidgetSettingsKeys.showSimKey(oldIdentity)])
            assertNotNull(prefs[WidgetSettingsKeys.showSimKey(activeIdentity)])
        }

    @Test
    fun `clearOrphanedSettings should handle all widget prefixes correctly`() =
        runTest {
            val identity = "icc_test"
            dataStore.edit { prefs ->
                prefs[WidgetSettingsKeys.showSimKey(identity)] = true
                prefs[WidgetSettingsKeys.showSimIconKey(identity)] = true
                prefs[WidgetSettingsKeys.showSimNameKey(identity)] = true
                prefs[WidgetSettingsKeys.showMinutesKey(identity)] = true
                prefs[WidgetSettingsKeys.showMinutesLimitKey(identity)] = true
                prefs[WidgetSettingsKeys.showTrafficKey(identity)] = true
                prefs[WidgetSettingsKeys.showTrafficLimitKey(identity)] = true
            }

            // Remove it
            repository.clearOrphanedSettings(emptyList())

            val prefs = dataStore.data.first()
            assertNull(prefs[WidgetSettingsKeys.showSimKey(identity)])
            assertNull(prefs[WidgetSettingsKeys.showSimIconKey(identity)])
            assertNull(prefs[WidgetSettingsKeys.showSimNameKey(identity)])
            assertNull(prefs[WidgetSettingsKeys.showMinutesKey(identity)])
            assertNull(prefs[WidgetSettingsKeys.showMinutesLimitKey(identity)])
            assertNull(prefs[WidgetSettingsKeys.showTrafficKey(identity)])
            assertNull(prefs[WidgetSettingsKeys.showTrafficLimitKey(identity)])
        }
}
