package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

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
                ) { File(tmpFolder.root, "test_sim_usage_${UUID.randomUUID()}.preferences_pb") }
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
                ) { File(tmpFolder.root, "test_sim_usage_${UUID.randomUUID()}.preferences_pb") }
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
