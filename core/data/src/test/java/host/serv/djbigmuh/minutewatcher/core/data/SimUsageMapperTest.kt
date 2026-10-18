package host.serv.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimUsageMapperTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `mapToSimUsage reads brand key when present`() =
        runTest {
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { tmpFolder.newFile("test_sim_usage_${System.currentTimeMillis()}.preferences_pb") }
            val mapper = SimUsageMapper()
            val identity = "test_sim"
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.simOperatorKey(identity)] = "MTS"
                prefs[DataStoreKeys.simOperatorBrandKeyKey(identity)] = "operator_mts"
                prefs[DataStoreKeys.slotIndexKey(identity)] = 0
            }

            val prefs = dataStore.data.first()
            val usage = mapper.mapToSimUsage(identity, prefs)

            assertEquals("MTS", usage.operator)
            assertEquals("operator_mts", usage.brandKey)
        }

    @Test
    fun `mapToSimUsage recovers missing brand key from operator name using tokenToResKey`() =
        runTest {
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { tmpFolder.newFile("test_sim_usage_${System.currentTimeMillis()}.preferences_pb") }
            val mapper = SimUsageMapper()
            val identity = "test_sim_legacy"
            dataStore.edit { prefs ->
                prefs[DataStoreKeys.simOperatorKey(identity)] = "MegaFon RUS"
                prefs[DataStoreKeys.slotIndexKey(identity)] = 0
            }

            val prefs = dataStore.data.first()
            val usage = mapper.mapToSimUsage(identity, prefs)

            assertEquals("MegaFon RUS", usage.operator)
            assertEquals("operator_megafon", usage.brandKey)
        }
}
