package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.domain.AppFunctionAvailability
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WidgetSettingsAttributionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var appDataStore: DataStore<Preferences>
    private lateinit var simDataStore: DataStore<Preferences>
    private lateinit var simWidgetVisibilityRepository: SimWidgetVisibilityRepositoryImpl
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var simIdentityRepository: SimIdentityRepository
    private lateinit var appFunctionAvailability: AppFunctionAvailability
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var repository: WidgetSettingsRepository

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Before
    fun setup() {
        initTestLogger()
        appDataStore = InMemoryPreferencesDataStore()
        simDataStore = InMemoryPreferencesDataStore()
        simWidgetVisibilityRepository = SimWidgetVisibilityRepositoryImpl(simDataStore)
        appSettingsRepository = mock()
        simIdentityRepository = mock()
        appFunctionAvailability = mock()
        migrationService = IdentityMigrationService(appDataStore, simDataStore)
        repository =
            WidgetSettingsRepository(
                appSettingsRepository = appSettingsRepository,
                simIdentityRepository = simIdentityRepository,
                simWidgetVisibilityRepository = simWidgetVisibilityRepository,
                appFunctionAvailability = appFunctionAvailability,
            )
    }

    @Test
    fun `widget settings attribute sim settings to identity without leak on swap`() =
        runTest {
            // 1. Setup SIM A in slot 0 and hide it
            val identityA = "icc_A"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identityA)
            val simStateA = SimSettingsState(showSim = false, lastKnownIdentity = identityA)
            val saveStateA = WidgetSettingsState(simStates = mapOf(0 to simStateA))

            repository.saveSimSettings(saveStateA)

            // Verify it is saved under identityA and NOT under sim_0
            val prefs = simDataStore.data.first()
            assertFalse(
                "Should have showSim=false for identityA",
                prefs[DataStoreKeys.showSimKey(identityA)] == true,
            )
            assertEquals(false, prefs[DataStoreKeys.showSimKey(identityA)])
            assertNull(
                "Should NOT have created slot-scoped key",
                prefs[DataStoreKeys.showSimKey("sim_0")],
            )

            // 2. Simulate SIM swap: SIM B in slot 0. Run migration.
            val identityB = "icc_B"
            val simIdentityB = SimIdentity(identityB, 102, 0, IdentitySource.ICCID, true)

            // Migration find slot-scoped keys and moves them. Since we didn't create one, nothing should leak.
            migrationService.migrateLegacyData(simIdentityB)

            // 3. Load settings for SIM B. It should have default values (showSim = true).
            val batch = simWidgetVisibilityRepository.getSimSettingsBatch(listOf(identityB)).first()
            val settingsB = batch[identityB]

            assertEquals("SIM B should NOT inherit 'hidden' state from SIM A", true, settingsB?.showSim)
        }

    @Test
    fun `legacy V1 data migration still works`() =
        runTest {
            // 1. Manually insert legacy V1 key (slot-scoped)
            val legacyKey = DataStoreKeys.showSimKey("sim_0")
            simDataStore.edit { it[legacyKey] = false }

            // 2. Run migration for a new SIM identity in slot 0
            val identityB = "icc_B"
            val simIdentityB = SimIdentity(identityB, 102, 0, IdentitySource.ICCID, true)
            migrationService.migrateLegacyData(simIdentityB)

            // 3. Verify data moved to the new identity
            val prefs = simDataStore.data.first()
            assertEquals(
                "Legacy data should have migrated to SIM B",
                false,
                prefs[DataStoreKeys.showSimKey(identityB)],
            )
            assertNull("Old legacy key should be removed", prefs[legacyKey])
        }
}
