package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.SimSettingsState
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
        }
}
