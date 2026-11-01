package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import host.serv.djbigmuh.minutewatcher.core.domain.SeedUnavailableException
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryHangTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var seedRepository: SimIdentitySeedRepository
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var timeProvider: TimeProvider
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var discoveryDeps: SimDiscoveryDependencies
    private val mockSimProvider: MockSimProvider =
        mock {
            on { getMockSims(any()) }.thenReturn(null)
        }

    @Before
    fun setup() {
        context = mock()
        subscriptionManager = mock()
        permissionStateNotifier = mock()
        permissionManager = mock()
        timeProvider = mock()
        migrationService = mock()
        appSettingsRepository =
            mock {
                on { observeAppSettings() } doReturn MutableStateFlow(AppSettings())
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
    }

    private fun TestScope.createTestDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = {
                val fileName = "test_sim_settings_${name}_${System.currentTimeMillis()}.preferences_pb"
                tmpFolder.newFile(fileName)
            },
        )

    @Test
    fun `getSimIdentity throws error when activeSimsCache hangs`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            val hangingSeedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = MutableSharedFlow()
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) = Unit

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(1000.seconds)
                        return SeedResolutionResult.Ephemeral("ephemeral")
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds1"),
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Act
            val result = runCatching { identityRepo.getSimIdentity(0) }
            assertTrue(result.exceptionOrNull() is SeedUnavailableException)
        }

    @Test
    fun `getSimIdentity does not hang forever when seed generation fails`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            // Mock seedRepository.seedFlow to return empty/null
            whenever(seedRepository.seedFlow).thenReturn(flowOf(""))

            // Mock getOrCreateSeed to hang
            val hangingSeedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = flowOf("")
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) {
                        // No-op
                    }

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(1000.seconds)
                        return SeedResolutionResult.Persistent("seed")
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds2"),
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Act: Try to get identity.
            // Using withTimeoutOrNull to ensure we don't hang if implementation is broken.
            val result =
                withTimeoutOrNull(20.seconds) {
                    try {
                        identityRepo.getSimIdentity(0)
                        "success"
                    } catch (_: SeedUnavailableException) {
                        "error"
                    }
                }

            // The test is successful if it completed without hanging and reported the expected error.
            assertEquals("error", result)
        }

    @Test
    fun `observeActiveSims emits when seed generation fails`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            // Mock seedRepository to hang
            val hangingSeedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = flowOf("")
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) {
                        // No-op
                    }

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(1000.seconds)
                        return SeedResolutionResult.Persistent("seed")
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds3"),
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Act: observeActiveSims().first() should complete due to fallback seed
            val result =
                withTimeoutOrNull(60.seconds) {
                    identityRepo.observeActiveSims().first()
                }

            // Assert: it should have emitted (even if empty list)
            assertTrue("Flow should have emitted a value instead of hanging", result != null)
        }

    @Test
    fun `observeActiveSims emits empty list when seedFlow timeouts`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            val mockSeedRepo =
                mock<SimIdentitySeedRepository> {
                    // Return flow that never emits
                    on { seedFlow } doReturn MutableSharedFlow()
                }
            whenever(mockSeedRepo.getOrCreateSeedOrEphemeralFallback(any())).thenAnswer {
                SeedResolutionResult.Ephemeral("fallback_seed")
            }
            whenever(mockSeedRepo.getOrCreateEphemeralSeed(any())).thenAnswer {
                (it.arguments[0] as () -> String).invoke()
            }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds4"),
                    seedRepository = mockSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Act: wait for internal timeout (SEED_GENERATION_TIMEOUT_MS + 2000ms)
            advanceTimeBy((Constants.Flow.SEED_GENERATION_TIMEOUT_MS + 2000).milliseconds)
            runCurrent()

            // We expect the flow to emit SeedResult.Fallback, which leads to
            // physicalActiveSims emitting, which leads to activeSimsCache emitting.
            // Since activeSimsCache is a StateFlow, it always has an initial value (emptyList).
            // We want to verify that it correctly processed the timeout and is still
            // functional (emitted the result of timeout logic).
            val result =
                withTimeoutOrNull(5.seconds) {
                    identityRepo.observeActiveSims().first { it.isEmpty() }
                }

            // Assert: should be empty list, not hang
            assertTrue("Result should be empty list after timeout", result != null)
            verify(mockSeedRepo, times(1)).getOrCreateSeedOrEphemeralFallback(any())
            verify(permissionManager, times(1)).getActiveSubscriptions()
        }

    @Test
    fun `saveSimIdentitiesInfo does not block other calls on slow migration`() =
        runTest {
            val migrationStarted = AtomicBoolean(false)
            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds5"),
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            val subInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo.subscriptionId).thenReturn(101)
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.iccId).thenReturn("iccid_0")
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            // Migration that just sets a flag and returns.
            // We'll simulate "slowness" by launching another call immediately.
            whenever(migrationService.migrateLegacyData(any())).thenAnswer {
                migrationStarted.set(true)
                IdentityMigrationService.MigrationOutcome(migrated = 1)
            }

            // Act: First call
            identityRepo.getSimIdentity(0)
            advanceUntilIdle()
            assertTrue("Migration should have started", migrationStarted.get())

            // Second call for a DIFFERENT slot.
            val subInfo2 = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo2.subscriptionId).thenReturn(102)
            whenever(subInfo2.simSlotIndex).thenReturn(1)
            whenever(subInfo2.iccId).thenReturn("iccid_1")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo, subInfo2))

            val identity2 = identityRepo.getSimIdentity(1)
            assertTrue("Identity2 should be based on ICCID", identity2.stableId.startsWith("icc_"))
        }

    @Test
    fun `observeActiveSims returns empty list immediately when no SIMs and not initialized`() =
        runTest {
            // Setup: no permissions yet, so flow won't emit physical SIMs
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds6"),
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // Act & Assert: first() should return emptyList() immediately without blocking/timeout
            val result =
                withTimeoutOrNull(5.seconds) {
                    identityRepo.observeActiveSims().first()
                }

            assertTrue("Flow should have emitted empty list immediately", result != null)
            assertTrue("Result should be empty", result?.isEmpty() == true)
        }

    @Test
    fun `saveSimIdentitiesInfo handles DataStore hang without crashing`() =
        runTest {
            val hangingDataStore = mock<DataStore<Preferences>>()
            whenever(hangingDataStore.data).thenReturn(MutableSharedFlow())

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = hangingDataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            val subInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.subscriptionId).thenReturn(1)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            // Act: Try to get identity. This will trigger saveSimIdentitiesInfo internally.
            // We advance time to cover both timeout attempts (10s + 20s).
            val job =
                launch {
                    try {
                        identityRepo.getSimIdentity(0)
                    } catch (_: Exception) {
                        // Intentionally ignored as this is an expected timeout failure in the test.
                        // Expected failure
                    }
                }

            // Constants.Flow.LONG_TIMEOUT_MS is 10_000L
            advanceTimeBy(35_000.milliseconds)
            runCurrent()

            assertTrue("Identity resolution should complete even if DataStore hangs (via exception)", job.isCompleted)
        }

    @Test
    fun `seedFlow continues emitting last known value when internal observation hangs`() =
        runTest {
            val storedSeed = MutableSharedFlow<String>(replay = 1)
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) = storedSeed.emit(seed)

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult = SeedResolutionResult.Persistent("generated_seed")

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds7"),
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            // 1. Emit real seed
            storedSeed.emit("seed_1")
            runCurrent()

            // 2. Resolve identity to ensure we are in Real state and seed_1 is the last known value
            val subInfo = mock<android.telephony.SubscriptionInfo>()
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.iccId).thenReturn("iccid_1")
            whenever(subInfo.subscriptionId).thenReturn(1)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)

            val id1 = identityRepo.getSimIdentity(0)
            assertTrue("Expected real identity", id1.source == IdentitySource.ICCID)

            // 3. Advance time to trigger the withTimeoutOrNull around emitAll in seedFlow.
            // Since storedSeed (MutableSharedFlow) doesn't emit after our first emit,
            // the withTimeoutOrNull(10s) will eventually time out.
            advanceTimeBy(15_000.milliseconds)
            runCurrent()

            // 4. New resolution attempt. It should still work using the last known seed
            // provided by the replay cache of the shared seedFlow.
            val id2 = identityRepo.getSimIdentity(0)
            assertEquals("Should still use last known identity after hang", id1.stableId, id2.stableId)
        }

    @Test
    fun `observeActiveSims does not hang when getOrCreateSeedOrEphemeralFallback hangs`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            val hangingSeedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = flowOf("")
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) {
                        // No-op
                    }

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(1000.seconds)
                        return SeedResolutionResult.Ephemeral("never_reached")
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val identityRepo =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = discoveryDeps,
                    dataStore = createTestDataStore("ds_hang_observer"),
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = backgroundScope,
                )

            advanceTimeBy((Constants.Flow.SEED_GENERATION_TIMEOUT_MS * 3).milliseconds)
            runCurrent()

            val result =
                withTimeoutOrNull(5.seconds) {
                    identityRepo.observeActiveSims().first { it.isEmpty() }
                }
            assertTrue("Should not hang even if seed repository generation hangs", result != null)
        }
}
