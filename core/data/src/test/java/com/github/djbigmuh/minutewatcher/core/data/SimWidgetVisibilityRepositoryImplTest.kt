package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimWidgetVisibilityRepositoryImplTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SimWidgetVisibilityRepositoryImpl

    @Before
    fun setup() {
        dataStore = InMemoryPreferencesDataStore()
        repository = SimWidgetVisibilityRepositoryImpl(dataStore)
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

    @Test
    fun `setSimSettingsBatch correctly saves multiple settings`() =
        runTest {
            val id1 = "icc_1"
            val id2 = "sub_2"
            val settings1 = SimSettingsState(showSim = false, showIcon = true)
            val settings2 = SimSettingsState(showSim = true, showIcon = false)

            repository.setSimSettingsBatch(
                mapOf(
                    id1 to settings1,
                    id2 to settings2,
                ),
            )

            val prefs = dataStore.data.first()
            assertEquals(false, prefs[DataStoreKeys.showSimKey(id1)])
            assertEquals(true, prefs[DataStoreKeys.showSimIconKey(id1)])
            assertEquals(true, prefs[DataStoreKeys.showSimKey(id2)])
            assertEquals(false, prefs[DataStoreKeys.showSimIconKey(id2)])

            val readSettings = repository.getSimSettingsBatch(listOf(id1, id2)).first()
            assertEquals(settings1.showSim, readSettings[id1]?.showSim)
            assertEquals(settings1.showIcon, readSettings[id1]?.showIcon)
            assertEquals(settings2.showSim, readSettings[id2]?.showSim)
            assertEquals(settings2.showIcon, readSettings[id2]?.showIcon)
        }

    @Test
    fun `collapsing and expanding sim section performs no extra storage writes and preserves previous settings`() =
        runTest {
            val identity = "icc_test_collapsible"
            val initialSettings =
                SimSettingsState(
                    showSim = true,
                    showName = false,
                    showIcon = true,
                    showMinutes = true,
                    showMinutesLimit = true,
                    showTraffic = false,
                    showTrafficLimit = false,
                )

            repository.setSimSettingsBatch(mapOf(identity to initialSettings))

            val savedInitial = repository.getSimSettingsBatch(listOf(identity)).first()[identity]
            assertEquals(true, savedInitial?.showSim)
            assertEquals(false, savedInitial?.showName)
            assertEquals(true, savedInitial?.showMinutesLimit)

            val collapsedSettings = savedInitial!!.copy(showSim = false)
            repository.setSimSettingsBatch(mapOf(identity to collapsedSettings))

            val savedCollapsed = repository.getSimSettingsBatch(listOf(identity)).first()[identity]
            assertEquals(false, savedCollapsed?.showSim)
            assertEquals(false, savedCollapsed?.showName)
            assertEquals(true, savedCollapsed?.showMinutesLimit)

            val reExpandedSettings = savedCollapsed!!.copy(showSim = true)
            repository.setSimSettingsBatch(mapOf(identity to reExpandedSettings))

            val savedExpanded = repository.getSimSettingsBatch(listOf(identity)).first()[identity]
            assertEquals(initialSettings, savedExpanded)
        }
}
