package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityPersistenceConflictTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var appDataStore: DataStore<Preferences>
    private lateinit var simDataStore: DataStore<Preferences>
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var countingSimDataStore: CountingDataStore

    private class CountingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var editCount = 0

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            editCount++
            return delegate.updateData(transform)
        }
    }

    private fun createService(scope: CoroutineScope) {
        appDataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
            ) { tmpFolder.newFile("test_app_settings_${System.currentTimeMillis()}.preferences_pb") }
        val realSimDataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
            ) { tmpFolder.newFile("test_sim_settings_${System.currentTimeMillis()}.preferences_pb") }
        countingSimDataStore = CountingDataStore(realSimDataStore)
        simDataStore = countingSimDataStore
        migrationService = IdentityMigrationService(appDataStore, simDataStore)
    }

    @Test
    fun `migration conflict does not block persistence and prevents retry loop`() =
        runTest {
            createService(backgroundScope)
            val persistence = SimIdentityPersistence(simDataStore, migrationService)
            val legacyKey = intPreferencesKey("minutes_used_sim_0")
            val identityId = "icc_X"
            val modernKey = intPreferencesKey("minutes_used_$identityId")
            val conflictKey = intPreferencesKey("conflict_minutes_used_sim_0")

            // 1. Setup conflict: legacy value 100, modern value 250
            simDataStore.edit {
                it[legacyKey] = 100
                it[modernKey] = 250
            }
            advanceUntilIdle()

            val identity =
                SimIdentity(
                    stableId = identityId,
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.ICCID,
                    isStable = true,
                )

            // 2. First call: triggers migration which results in conflict
            persistence.saveSimIdentitiesInfo(listOf(identity))
            advanceUntilIdle()

            val prefs = simDataStore.data.first()
            // Modern wins, legacy backed up, legacy REMOVED
            assertEquals(250, prefs[modernKey])
            assertEquals(100, prefs[conflictKey])
            assertNull("Legacy key must be removed", prefs[legacyKey])
            // slotIndex persisted for the identity
            assertEquals(0, prefs[DataStoreKeys.slotIndexKey(identityId)])

            val editCountAfterFirst = countingSimDataStore.editCount

            // 3. Second call: should NOT trigger migration again and should NOT call edit
            persistence.saveSimIdentitiesInfo(listOf(identity))
            advanceUntilIdle()

            assertEquals(
                "Should not perform additional edits for already persisted/migrated identity",
                editCountAfterFirst,
                countingSimDataStore.editCount,
            )
        }
}
