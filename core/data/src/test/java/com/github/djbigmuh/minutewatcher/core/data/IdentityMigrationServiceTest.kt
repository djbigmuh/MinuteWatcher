package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.djbigmuh.minutewatcher.core.common.annotation.RunBlockingBridge
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityMigrationServiceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var appDataStore: DataStore<Preferences>
    private lateinit var simDataStore: DataStore<Preferences>

    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Before
    fun setup() {
        appDataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
            ) { tmpFolder.newFile("test_app_settings.preferences_pb") }
        simDataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
            ) { tmpFolder.newFile("test_sim_settings.preferences_pb") }
    }

    @Test
    fun `migrates legacy preferences to modern format`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            appDataStore.edit { it[legacyKey] = 123 }
            advanceUntilIdle()

            val outcome =
                service.migrateLegacyData(
                    SimIdentity(
                        stableId = identityId,
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            advanceUntilIdle()

            assertTrue(outcome.migrated > 0)
            assertEquals(0, outcome.failures)
            val prefs = appDataStore.data.first()
            assertEquals(123, prefs[newKey])
            assertEquals(null, prefs[legacyKey])
        }

    @Test
    fun `metadata keys are excluded from suffix migration and cleaned up`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyDataKey = intPreferencesKey("minutes_used_icc_OLD")
            val legacyMarkerKey = intPreferencesKey("subscription_id_icc_OLD")
            val activeIdentity =
                SimIdentity(
                    stableId = "sub_7",
                    subscriptionId = 7,
                    slotIndex = 0,
                    source = IdentitySource.SUBSCRIPTION_ID,
                    isStable = true,
                )
            val newDataKey = intPreferencesKey("minutes_used_sub_7")
            val markerKeyInNew = intPreferencesKey("subscription_id_sub_7")

            simDataStore.edit {
                it[legacyDataKey] = 100
                it[legacyMarkerKey] = 7 // Matches subId 7
            }
            advanceUntilIdle()

            val outcome = service.migrateLegacyData(activeIdentity)
            advanceUntilIdle()

            assertEquals(1, outcome.migrated) // Only minutes_used_icc_OLD migrated
            val prefs = simDataStore.data.first()
            assertEquals(100, prefs[newDataKey])
            assertNull(prefs[legacyMarkerKey]) // Cleaned up during migration
            assertNull(prefs[markerKeyInNew]) // Not migrated from suffix
        }

    @Test
    fun `conflict metadata keys are cleaned up`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val conflictKey = intPreferencesKey("conflict_subscription_id_icc_OLD")
            simDataStore.edit { it[conflictKey] = 5 }
            advanceUntilIdle()

            val activeIdentity =
                SimIdentity(
                    stableId = "sub_7",
                    subscriptionId = 7,
                    slotIndex = 0,
                    source = IdentitySource.SUBSCRIPTION_ID,
                    isStable = true,
                )

            service.migrateLegacyData(activeIdentity)
            advanceUntilIdle()

            val prefs = simDataStore.data.first()
            assertNull(prefs[conflictKey])
        }

    @Test
    fun `custom sim operator keys are cleaned up`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val customOperatorKey = stringPreferencesKey("custom_sim_operator_test_id")
            simDataStore.edit { it[customOperatorKey] = "Custom Operator" }
            advanceUntilIdle()

            val activeIdentity =
                SimIdentity(
                    stableId = "sub_7",
                    subscriptionId = 7,
                    slotIndex = 0,
                    source = IdentitySource.SUBSCRIPTION_ID,
                    isStable = true,
                )

            service.migrateLegacyData(activeIdentity)
            advanceUntilIdle()

            val prefs = simDataStore.data.first()
            assertNull(prefs[customOperatorKey])
        }

    @Test
    fun `has legacy data`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            assertEquals(false, service.hasLegacyData())

            appDataStore.edit { it[intPreferencesKey("some_key_sim_0")] = 1 }
            assertEquals(true, service.hasLegacyData())

            val outcome =
                service.migrateLegacyData(
                    SimIdentity(
                        stableId = "some_id",
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            assertTrue(outcome.migrated > 0)
            assertNotEquals(true, service.hasLegacyData(setOf("some_id")))
        }

    @Test
    fun `migration slot 1`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_1")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            appDataStore.edit { it[legacyKey] = 42 }

            val outcome =
                service.migrateLegacyData(
                    SimIdentity(
                        stableId = identityId,
                        subscriptionId = 1,
                        slotIndex = 1,
                        source = IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            assertTrue(outcome.migrated > 0)

            val prefs = appDataStore.data.first()
            assertEquals(42, prefs[newKey])
            assertEquals(null, prefs[legacyKey])
        }

    @Test
    fun `has legacy data patterns`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            assertEquals(false, service.hasLegacyData())

            // V1
            appDataStore.edit { it[intPreferencesKey("key_sim_0")] = 1 }
            assertEquals(true, service.hasLegacyData())
            appDataStore.edit { it.clear() }

            // Sub pattern
            simDataStore.edit { it[intPreferencesKey("key_sub_123")] = 1 }
            assertEquals(true, service.hasLegacyData())
            simDataStore.edit { it.clear() }

            // ICC pattern
            appDataStore.edit { it[intPreferencesKey("key_icc_abc")] = 1 }
            assertEquals(true, service.hasLegacyData())
            appDataStore.edit { it.clear() }

            // Mapping marker
            simDataStore.edit { it[intPreferencesKey("subscription_id_icc_abc")] = 1 }
            assertEquals(true, service.hasLegacyData())
            simDataStore.edit { it.clear() }

            // Non-legacy
            appDataStore.edit { it[intPreferencesKey("app_theme")] = 1 }
            assertEquals(false, service.hasLegacyData())
        }

    @Test
    fun `has legacy data ignores active identities`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            // Keys that contain _sub_ or _icc_ but belong to active identities should not be legacy
            simDataStore.edit { it[intPreferencesKey("minutes_used_sub_123")] = 10 }
            appDataStore.edit { it[intPreferencesKey("traffic_used_icc_abc")] = 100 }

            // If we don't know active identities, they might still look like legacy
            assertEquals(true, service.hasLegacyData())

            // If they are known active, they are not legacy
            assertEquals(false, service.hasLegacyData(setOf("sub_123", "icc_abc")))
        }

    @Test
    fun `has legacy data schema version check`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            // Populate with legacy-looking data
            appDataStore.edit { it[intPreferencesKey("key_sub_123")] = 1 }

            assertEquals(true, service.hasLegacyData())

            // Mark schema as current
            service.markSchemaCurrent()

            // Now hasLegacyData should return false immediately regardless of keys
            assertEquals(false, service.hasLegacyData())
        }

    @Test
    fun `migration cleans up mapping markers`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val subId = 5
            val legacyStableId = "icc_some_hash"

            // Setup mapping marker and legacy data
            simDataStore.edit {
                it[intPreferencesKey("subscription_id_$legacyStableId")] = subId
                it[intPreferencesKey("data_$legacyStableId")] = 100
            }

            assertEquals(true, service.hasLegacyData())

            val outcome =
                service.migrateLegacyData(
                    SimIdentity(
                        stableId = "sub_$subId",
                        subscriptionId = subId,
                        slotIndex = 0,
                        source = IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )
            assertTrue(outcome.migrated > 0)

            val prefs = simDataStore.data.first()
            // Data migrated
            assertEquals(100, prefs[intPreferencesKey("data_sub_$subId")])
            // Marker removed
            assertFalse(prefs.asMap().keys.any { it.name.startsWith("subscription_id_icc_") })

            // With active identity, should return false
            assertEquals(false, service.hasLegacyData(setOf("sub_$subId")))

            simDataStore.edit { it.clear() }
            simDataStore.edit { it[intPreferencesKey("app_theme")] = 1 }
            assertEquals(false, service.hasLegacyData())
        }

    @Test
    fun `migration conflict preserves modern value and backs up legacy`() =
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
            val outcome =
                service.migrateLegacyData(
                    SimIdentity(
                        stableId = identityId,
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )

            assertEquals(0, outcome.migrated)
            assertTrue("Conflict count should be > 0", outcome.conflicts > 0)
            assertEquals(0, outcome.failures)
            assertFalse("Partial failure should be false for resolved conflicts", outcome.partialFailure)

            val prefs = appDataStore.data.first()
            // 3. Modern value should be preserved
            assertEquals(222, prefs[newKey])
            // 4. Legacy value should be moved to conflict key
            assertEquals(111, prefs[conflictKey])
            // 5. Original old key should be REMOVED (Policy Fix)
            assertNull("Legacy key must be removed after conflict resolution", prefs[legacyKey])
            // 6. hasLegacyData() should return false (Conflict resolved)
            assertEquals(false, service.hasLegacyData())
        }

    @Test
    fun `modern sub keys are not treated as legacy when schema is current`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            service.markSchemaCurrent()

            simDataStore.edit { it[intPreferencesKey("minutes_used_sub_1")] = 10 }

            assertEquals(false, service.hasLegacyData())
        }

    @Test
    fun `modern icc keys are not treated as legacy when schema is current`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            service.markSchemaCurrent()

            appDataStore.edit { it[intPreferencesKey("traffic_limit_icc_abc")] = 1000 }

            assertEquals(false, service.hasLegacyData())
        }

    @Test
    fun `legacy sim_0 keys are detected when schema is old`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            appDataStore.edit { it[intPreferencesKey("some_key_sim_0")] = 1 }

            assertEquals(true, service.hasLegacyData())
        }

    @Test
    fun `active identity keys are excluded from legacy detection`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            val activeIdentity = "sub_5"
            simDataStore.edit { it[intPreferencesKey("minutes_used_$activeIdentity")] = 20 }

            assertEquals(false, service.hasLegacyData(activeIdentities = setOf(activeIdentity)))
        }

    @Test
    fun `hasLegacyData returns null on DataStore exception`() =
        testScope.runTest {
            val corruptedDataStore: DataStore<Preferences> = mock()
            whenever(corruptedDataStore.data).thenReturn(flow { throw IOException("Disk read error") })

            val service = IdentityMigrationService(corruptedDataStore, simDataStore)
            val result = service.hasLegacyData()
            assertNull(result)
        }

    @Test
    @RunBlockingBridge(
        "Cancellation exception assertion bridge",
    )
    fun `hasLegacyData rethrows CancellationException`() =
        testScope.runTest {
            val canceledDataStore: DataStore<Preferences> = mock()
            whenever(canceledDataStore.data).thenReturn(flow { throw CancellationException("Canceled") })

            val service = IdentityMigrationService(canceledDataStore, simDataStore)
            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    service.hasLegacyData()
                }
            }
        }

    @Test
    fun `hasLegacyData returns false without reading sim DataStore when schema is current`() =
        testScope.runTest {
            appDataStore.edit { it[DataStoreKeys.DATA_SCHEMA_VERSION] = DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION }

            val unreadSimDataStore: DataStore<Preferences> = mock()
            val service = IdentityMigrationService(appDataStore, unreadSimDataStore)

            val result = service.hasLegacyData()
            assertNotEquals(true, result)
        }

    @Test
    fun `migrateLegacyData reports failure and releases mutex on datastore exception`() =
        testScope.runTest {
            val corruptedDataStore: DataStore<Preferences> = mock()
            val legacyKey = stringPreferencesKey("test_key_sim_0")
            val prefs =
                androidx.datastore.preferences.core.emptyPreferences().toMutablePreferences().apply {
                    this[legacyKey] = "legacy_value"
                }
            whenever(corruptedDataStore.data).thenReturn(kotlinx.coroutines.flow.flowOf(prefs))
            whenever(corruptedDataStore.edit(any())).thenThrow(RuntimeException("Write failed"))

            val service = IdentityMigrationService(corruptedDataStore, simDataStore)
            val activeIdentity =
                SimIdentity(
                    stableId = "new_id",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.SUBSCRIPTION_ID,
                    isStable = false,
                )

            val outcome = service.migrateLegacyData(activeIdentity)
            assertTrue(outcome.partialFailure)
            assertEquals(1, outcome.failures)

            // Verify mutex is released: subsequent call (with a working store) succeeds without blocking
            val service2 = IdentityMigrationService(appDataStore, simDataStore)
            val outcome2 = service2.migrateLegacyData(activeIdentity)
            assertTrue(!outcome2.partialFailure)
        }

    @Test
    fun `concurrent migration does not corrupt data`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = intPreferencesKey("test_key_sim_0")
            val identityId = "new_identity"
            val newKey = intPreferencesKey("test_key_$identityId")

            appDataStore.edit { it[legacyKey] = 123 }
            advanceUntilIdle()

            val identity =
                SimIdentity(
                    stableId = identityId,
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.SUBSCRIPTION_ID,
                    isStable = false,
                )

            val results = mutableListOf<IdentityMigrationService.MigrationOutcome>()
            val jobs =
                List(5) {
                    launch {
                        results.add(service.migrateLegacyData(identity))
                    }
                }

            withTimeoutOrNull(30.seconds) { jobs.joinAll() }
                ?: fail("Timed out waiting for concurrent migrations to complete")
            advanceUntilIdle()

            val prefs = appDataStore.data.first()
            assertEquals(123, prefs[newKey])
            assertNull(prefs[legacyKey])
            assertFalse(prefs.asMap().keys.any { it.name.startsWith("conflict_") })
            assertEquals(1, results.count { it.hasChanges() })
        }

    @Test
    fun `migration failure logged and reported`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            val outcome =
                service.migrateLegacyData(
                    SimIdentity(
                        stableId = "id",
                        subscriptionId = 1,
                        slotIndex = 0,
                        source = IdentitySource.SUBSCRIPTION_ID,
                        isStable = false,
                    ),
                )

            assertEquals(0, outcome.migrated)
            assertEquals(0, outcome.failures)
            assertFalse(outcome.hasChanges())
            assertNotEquals(true, service.hasLegacyData())
        }

    @Test
    fun `migrates string set legacy preferences to modern format`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = stringSetPreferencesKey("blocked_numbers_sim_0")
            val identityId = "new_identity"
            val newKey = stringSetPreferencesKey("blocked_numbers_$identityId")
            val stringSet = setOf("123", "456")

            appDataStore.edit { it[legacyKey] = stringSet }
            advanceUntilIdle()

            val outcome =
                withTimeout(10.seconds) {
                    service.migrateLegacyData(
                        SimIdentity(
                            stableId = identityId,
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.SUBSCRIPTION_ID,
                            isStable = false,
                        ),
                    )
                }
            advanceUntilIdle()

            assertEquals(1, outcome.migrated)
            assertEquals(0, outcome.conflicts)
            assertEquals(0, outcome.failures)
            assertFalse(outcome.partialFailure)

            val prefs = appDataStore.data.first()
            assertEquals(stringSet, prefs[newKey])
            assertNull(prefs[legacyKey])
        }

    @Test
    fun `migration conflict with string set preserves modern value and backs up legacy`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = stringSetPreferencesKey("blocked_numbers_sim_0")
            val identityId = "new_identity"
            val newKey = stringSetPreferencesKey("blocked_numbers_$identityId")
            val conflictKey = stringSetPreferencesKey("conflict_blocked_numbers_sim_0")

            val oldSet = setOf("123", "456")
            val newSet = setOf("789")

            appDataStore.edit {
                it[legacyKey] = oldSet
                it[newKey] = newSet
            }
            advanceUntilIdle()

            val outcome =
                withTimeout(10.seconds) {
                    service.migrateLegacyData(
                        SimIdentity(
                            stableId = identityId,
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.SUBSCRIPTION_ID,
                            isStable = false,
                        ),
                    )
                }
            advanceUntilIdle()

            assertEquals(0, outcome.migrated)
            assertEquals(1, outcome.conflicts)
            assertEquals(0, outcome.failures)
            assertFalse(outcome.partialFailure)

            val prefs = appDataStore.data.first()
            assertEquals(newSet, prefs[newKey])
            assertEquals(oldSet, prefs[conflictKey])
            assertNull(prefs[legacyKey])
        }

    @Test
    fun `migration with identical string set value succeeds without creating conflict`() =
        runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)
            val legacyKey = stringSetPreferencesKey("blocked_numbers_sim_0")
            val identityId = "new_identity"
            val newKey = stringSetPreferencesKey("blocked_numbers_$identityId")
            val conflictKey = stringSetPreferencesKey("conflict_blocked_numbers_sim_0")

            val set = setOf("123", "456")

            appDataStore.edit {
                it[legacyKey] = set
                it[newKey] = set
            }
            advanceUntilIdle()

            val outcome =
                withTimeout(10.seconds) {
                    service.migrateLegacyData(
                        SimIdentity(
                            stableId = identityId,
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.SUBSCRIPTION_ID,
                            isStable = false,
                        ),
                    )
                }
            advanceUntilIdle()

            assertEquals(1, outcome.migrated)
            assertEquals(0, outcome.conflicts)
            assertEquals(0, outcome.failures)
            assertFalse(outcome.partialFailure)

            val prefs = appDataStore.data.first()
            assertEquals(set, prefs[newKey])
            assertNull(prefs[conflictKey])
            assertNull(prefs[legacyKey])
        }

    @Test
    fun `migration steps set schema version and handle step transitions`() =
        testScope.runTest {
            val service = IdentityMigrationService(appDataStore, simDataStore)

            val legacyAppKey = intPreferencesKey("app_setting_sim_0")
            val legacySimKey = intPreferencesKey("minutes_used_sim_0")
            val identity =
                SimIdentity(
                    stableId = "icc_test_1",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.ICCID,
                    isStable = true,
                )

            appDataStore.edit { it[legacyAppKey] = 100 }
            simDataStore.edit { it[legacySimKey] = 200 }

            val outcome1 = service.migrateLegacyData(identity)
            assertTrue(outcome1.migrated > 0)
            assertEquals(0, outcome1.failures)

            service.markSchemaCurrent()

            val appPrefs = appDataStore.data.first()
            assertEquals(
                DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION,
                appPrefs[DataStoreKeys.DATA_SCHEMA_VERSION],
            )
            assertEquals(100, appPrefs[intPreferencesKey("app_setting_icc_test_1")])
            assertNull(appPrefs[legacyAppKey])

            val simPrefs = simDataStore.data.first()
            assertEquals(200, simPrefs[intPreferencesKey("minutes_used_icc_test_1")])
            assertNull(simPrefs[legacySimKey])
        }

    @Test
    fun `migration process restart after mid-migration termination retains data integrity`() =
        testScope.runTest {
            val legacyAppKey = intPreferencesKey("limit_sim_0")
            val legacySimKey = intPreferencesKey("used_sim_0")
            val identity =
                SimIdentity(
                    stableId = "icc_restart_1",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.ICCID,
                    isStable = true,
                )

            appDataStore.edit { it[legacyAppKey] = 500 }
            simDataStore.edit { it[legacySimKey] = 250 }

            appDataStore.edit {
                it.remove(legacyAppKey)
                it[intPreferencesKey("limit_icc_restart_1")] = 500
            }

            assertNull(appDataStore.data.first()[DataStoreKeys.DATA_SCHEMA_VERSION])

            val service2 = IdentityMigrationService(appDataStore, simDataStore)
            val outcome = service2.migrateLegacyData(identity)

            assertEquals(0, outcome.failures)
            service2.markSchemaCurrent()

            val appPrefs = appDataStore.data.first()
            val simPrefs = simDataStore.data.first()

            assertEquals(500, appPrefs[intPreferencesKey("limit_icc_restart_1")])
            assertEquals(250, simPrefs[intPreferencesKey("used_icc_restart_1")])
            assertNull(appPrefs[legacyAppKey])
            assertNull(simPrefs[legacySimKey])
            assertEquals(
                DataStoreKeys.CURRENT_DATA_SCHEMA_VERSION,
                appPrefs[DataStoreKeys.DATA_SCHEMA_VERSION],
            )
        }
}
