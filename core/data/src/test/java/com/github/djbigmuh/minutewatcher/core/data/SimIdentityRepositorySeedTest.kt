package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class SimIdentityRepositorySeedTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var discoveryDeps: SimDiscoveryDependencies
    private val mockSimProvider: MockSimProvider =
        mock {
            on { getMockSims(any()) }.thenReturn(null)
        }

    private val permissionChanges = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()
        migrationService = mock()
        appSettingsRepository =
            mock {
                on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
            }
        whenever(permissionStateNotifier.changes).doReturn(permissionChanges)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    @Test
    fun `seedFlow recovers from fallback when seed eventually becomes available`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { tmpFolder.newFile("test_sim_settings.preferences_pb") }

            // Mock a subscription info beforehand
            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")
            whenever(mockSubInfo.displayName).thenReturn("SIM 1")
            whenever(mockSubInfo.carrierName).thenReturn("Carrier 1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            val storedSeed = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) {
                        storedSeed.emit(seed)
                    }

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        val current = storedSeed.replayCache.firstOrNull() ?: ""
                        if (current.isEmpty()) {
                            delay(1.seconds)
                        }
                        val seed = current.ifEmpty { generator() }
                        return SeedResolutionResult.Ephemeral(seed)
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock(),
                    scope = backgroundScope,
                )

            // Wait until seedFlow transitions to Fallback naturally
            testScheduler.advanceUntilIdle()

            // Check state - should return identity (even on fallback seed)
            val initialIdentity = identityRepo.getSimIdentity(0)
            assertTrue("Should return fallback identity", initialIdentity.stableId.isNotEmpty())

            // Now provide a real seed
            storedSeed.emit("real_seed")
            // Wait for the change to propagate to the observer's seedFlow
            backgroundScope.launch {
                identityRepo.observeActiveSims().collect { }
            }
            // Explicitly wait for the real seed to appear in the flow
            identityRepo.getActiveIdentities().first { it.isNotEmpty() }
            testScheduler.advanceUntilIdle()

            // Check recovery
            val realIdentity = identityRepo.getSimIdentity(0)
            assertTrue(
                "Expected identity based on real_seed, got: ${realIdentity.stableId}",
                realIdentity.stableId.startsWith("icc_"),
            )
            assertEquals(IdentitySource.ICCID, realIdentity.source)
        }

    @Test
    fun `getSimIdentity uses fallback cache and clears it on real seed`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { tmpFolder.newFile("test_sim_settings_cache.preferences_pb") }

            val storedSeed = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) = storedSeed.emit(seed)

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(1.seconds)
                        return SeedResolutionResult.Ephemeral("generated")
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock(),
                    scope = backgroundScope,
                )

            // 1. Advance to Fallback state
            testScheduler.advanceUntilIdle()

            // 2. First call - should return identity using fallback seed
            // Mock a subscription so it doesn't throw SimNotFoundException
            val mockSubInfoFallback = mock<SubscriptionInfo>()
            whenever(mockSubInfoFallback.subscriptionId).thenReturn(99)
            whenever(mockSubInfoFallback.simSlotIndex).thenReturn(0)
            whenever(mockSubInfoFallback.iccId).thenReturn("icc_fallback")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfoFallback))

            val initialId = identityRepo.getSimIdentity(0)
            assertTrue("Should return identity using fallback", initialId.stableId.isNotEmpty())

            // 3. Transition to Real seed
            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            storedSeed.emit("real_seed")
            // Wait for propagation
            identityRepo.getActiveIdentities().first { it.isNotEmpty() }
            testScheduler.advanceUntilIdle()

            // 4. Cache should be cleared, call should return real identity
            val id3 = identityRepo.getSimIdentity(0)
            assertTrue("Should return real ID now, got ${id3.stableId}", id3.stableId.startsWith("icc_"))
        }

    @Test
    fun migrationIsCalledWhenTransitioningFromFallbackToRealSeed() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                ) { tmpFolder.newFile("test_sim_migration.preferences_pb") }

            val storedSeed = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) = storedSeed.emit(seed)

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(1.seconds)
                        return SeedResolutionResult.Ephemeral("generated")
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock(),
                    scope = backgroundScope,
                )

            // 1. Mock a subscription info
            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            // 2. Advance to Fallback state
            testScheduler.advanceUntilIdle()

            // 3. Transition to Real seed
            storedSeed.emit("real_seed")
            // Wait for propagation
            identityRepo.getActiveIdentities().first { it.isNotEmpty() }
            testScheduler.advanceUntilIdle()

            // 4. Trigger identity observation to initiate migration
            identityRepo.getSimIdentity(0)

            // 5. Verify migration was called for the new identity
            org.mockito.kotlin.verify(migrationService).migrateLegacyData(
                org.mockito.kotlin.check { identity ->
                    assertEquals(0, identity.slotIndex)
                    assertTrue(identity.stableId.startsWith("icc_"))
                    assertEquals(IdentitySource.ICCID, identity.source)
                },
            )
        }
}
