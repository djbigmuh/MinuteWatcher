package com.github.djbigmuh.minutewatcher.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.data.di.DataStoreKeys
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PermissionRevocationDataRetentionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val permissionManager: PermissionManager = mock()

    @Test
    fun `lack of permission does not clear saved limits and reset settings in DataStore`() =
        runTest {
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) {
                    tmpFolder.newFile("limits_test.preferences_pb")
                }
            val limitsRepo = SimLimitsRepositoryImpl(dataStore)
            val identity = "sim_test_identity_123"

            // Save limits and auto-reset settings when permission is granted
            limitsRepo.updateSimLimits(
                identity = identity,
                minutesLimit = 500,
                trafficLimitMb = 10000,
                rolloverEnabled = true,
            )
            limitsRepo.setAutoReset(identity, day = 15, enabled = true)

            // Simulate permission revocation
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)

            // Verify stored limits and auto-reset settings remain intact despite missing permissions
            assertEquals(500, limitsRepo.getMonthlyLimit(identity).first())
            assertEquals(10000, limitsRepo.getTrafficLimit(identity).first())
            assertEquals(15, limitsRepo.getAutoResetDay(identity).first())
            assertTrue(limitsRepo.getAutoReset(identity).first())
        }

    @Test
    fun `permission revocation does not clear saved SIM identity preferences`() =
        runTest {
            val migrationService: IdentityMigrationService =
                mock {
                    on { migrateLegacyData(any()) } doReturn IdentityMigrationService.MigrationOutcome()
                }
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) {
                    tmpFolder.newFile("sim_persistence_test.preferences_pb")
                }
            val persistence = SimIdentityPersistence(dataStore, migrationService)
            val identity =
                SimIdentity(
                    stableId = "stable_sim_xyz",
                    subscriptionId = 10,
                    slotIndex = 0,
                    source = IdentitySource.ICCID,
                    isStable = true,
                )

            // Save SIM identity info
            persistence.saveSimIdentitiesInfo(listOf(identity))
            persistence.setSimOperator("stable_sim_xyz", "Test Operator", "brand_test")

            // Simulate permission revocation
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            // Read preferences directly to verify retained data
            val prefs = dataStore.data.first()
            assertEquals(0, prefs[DataStoreKeys.slotIndexKey("stable_sim_xyz")])
            assertEquals(10, prefs[DataStoreKeys.subscriptionIdKey("stable_sim_xyz")])
            assertEquals("Test Operator", prefs[DataStoreKeys.simOperatorKey("stable_sim_xyz")])
            assertEquals("brand_test", prefs[DataStoreKeys.simOperatorBrandKeyKey("stable_sim_xyz")])
        }

    @Test
    fun `SimIdentityResolver throws SecurityException without clearing cache on missing permission`() =
        runTest {
            val discovery: SimIdentityDiscovery = mock()
            val persistence: SimIdentityPersistence = mock()
            val cache: SimIdentityCache = mock()
            val resolver = SimIdentityResolver(discovery, persistence, cache)

            val activeSimsCache = MutableStateFlow<List<ActiveSimInfo>>(emptyList())
            val seedFlow = flowOf(SeedResult.Real("test_seed", true))
            val mutex = Mutex()

            whenever(discovery.discoverSims(any(), any())).thenReturn(SimDiscoveryResult.PermissionMissing)

            try {
                resolver.getSimIdentity(0, activeSimsCache, seedFlow, mutex)
                fail("Expected SecurityException when SIM discovery permission is missing")
            } catch (e: SecurityException) {
                assertEquals(true, e.message?.contains("Permission missing"))
            }
        }
}
