package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.InvalidSlotException
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryImplResolutionTest {
    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var discoveryDeps: SimDiscoveryDependencies
    private val mockSimProvider: MockSimProvider =
        mock {
            on { getMockSims(any()) }.thenReturn(null)
        }
    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)
    private val appScope = CoroutineScope(testDispatcher + Job())

    @Before
    fun setup() {
        initTestLogger()
        context = mock()
        subscriptionManager = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()
        migrationService =
            mock {
                on { migrateLegacyData(any()) } doReturn IdentityMigrationService.MigrationOutcome()
            }
        appSettingsRepository =
            mock {
                on { observeAppSettings() } doReturn
                    MutableStateFlow(
                        AppSettings(),
                    )
            }

        seedRepository =
            mock {
                on { seedFlow } doReturn flowOf("test_seed")
                on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn SeedResolutionResult.Persistent("test_seed")
            }
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )

        dataStore = InMemoryPreferencesDataStore()
    }

    private fun createTestDataStore(): DataStore<Preferences> = InMemoryPreferencesDataStore()

    @Test
    fun `active SIM in slot 1 returns correct slot index in usage`() =
        runTest(testDispatcher) {
            val ds = createTestDataStore()
            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = ds,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = appScope,
                )
            val usageRepo = SimUsageRepositoryImpl(ds, timeProvider, SimUsageMapper(), SimUsageResetLogic())

            // Mock a SIM in Slot 1 (index 1)
            val mockInfo = mock<SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(1)
            whenever(mockInfo.subscriptionId).thenReturn(2)
            whenever(mockInfo.iccId).thenReturn("test_iccid_slot1")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // Discovery
            val sims = identityRepo.observeActiveSims().first { it.isNotEmpty() }
            val identity = sims.first().identity.stableId

            // Allow DataStore update to propagate and ensure all coroutines finish
            testScheduler.advanceUntilIdle()
            yield()

            // Verify usage has slot 1
            val usage = usageRepo.getSimUsage(identity).first()
            assertEquals("Wrong slot index for identity $identity", 1, usage.slotIndex)
            assertEquals("Wrong subscription ID for identity $identity", 2, usage.subscriptionId)
        }

    @Test
    fun `getIdentity does not trigger edit when unchanged`() =
        runTest(testDispatcher) {
            val ds = createTestDataStore()
            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = ds,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = appScope,
                )

            val mockInfo = mock<SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(0)
            whenever(mockInfo.subscriptionId).thenReturn(1)
            whenever(mockInfo.iccId).thenReturn("test_iccid")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // First call - should write
            val id1 = identityRepo.getIdentity(0)

            // Second call - should use cache and NOT write
            val id2 = identityRepo.getIdentity(0)

            assertEquals(id1, id2)
        }

    @Test(expected = IllegalStateException::class)
    fun `getSimIdentity throws when permissions missing`() =
        runTest(testDispatcher) {
            val ds = createTestDataStore()
            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = ds,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = appScope,
                )

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            identityRepo.getSimIdentity(0)
        }

    @Test
    fun `observeActiveSims handles save identity error`() =
        runTest(testDispatcher) {
            val mockDataStore: DataStore<Preferences> = mock()
            whenever(mockDataStore.data)
                .thenReturn(flowOf(emptyPreferences()))
            whenever(mockDataStore.updateData(any()))
                .thenThrow(RuntimeException("Test disk full"))

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = mockDataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = appScope,
                )

            val mockInfo = mock<SubscriptionInfo>()
            whenever(mockInfo.simSlotIndex).thenReturn(0)
            whenever(mockInfo.subscriptionId).thenReturn(1)
            whenever(mockInfo.iccId).thenReturn("test_iccid")

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockInfo))

            // This should NOT throw exception because of the new try-catch
            val sims = identityRepo.observeActiveSims().first { it.isNotEmpty() }

            assertEquals(1, sims.size)
            assertEquals(0, sims.first().slotIndex)
        }

    @Test(expected = InvalidSlotException::class)
    fun `getSimIdentity with invalid slot throws InvalidSlotException`() =
        runTest(testDispatcher) {
            val ds = createTestDataStore()
            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = ds,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = appScope,
                )

            identityRepo.getSimIdentity(-1)
        }
}
