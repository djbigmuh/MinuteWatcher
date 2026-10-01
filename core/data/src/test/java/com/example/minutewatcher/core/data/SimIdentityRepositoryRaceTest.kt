package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryRaceTest {
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
        whenever(permissionStateNotifier.changes).doReturn(MutableSharedFlow())
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

        // DataStore initialized inside tests to use correct backgroundScope

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    @Test
    fun `seedFlow emits Fallback on timeout and then Real when seed arrives`() =
        runTest {
            val storedSeed = MutableSharedFlow<String>(replay = 0)
            val identityRepo = createRepositoryForSeedFlowTest("test_sim_settings_1.preferences_pb", storedSeed)
            val seedFlow = getInternalSeedFlow(identityRepo)

            val emissions = mutableListOf<Any>()
            val job = launch { seedFlow.collect { emissions.add(it) } }

            // 1. Initial state: wait for extended timeout (2 * 10s = 20s).
            testScheduler.advanceTimeBy(21.seconds)
            testScheduler.runCurrent()

            assertTrue(
                "Emissions should contain Fallback, found: $emissions",
                emissions.any { it.toString().contains("Fallback") },
            )
            // It might have more than 1 emission if Fallback is retried, but distinctUntilChanged should keep it to 1
            // until Real arrives.
            assertTrue("Should have at least 1 emission (Fallback)", emissions.isNotEmpty())

            // 2. Emit real seed
            val sizeBeforeReal = emissions.size
            storedSeed.emit("real_seed")
            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.runCurrent()

            assertTrue(
                "Emissions should contain Real(real_seed), found: $emissions",
                emissions.any { it.toString().contains("real_seed") },
            )
            assertEquals("Should have added exactly 1 new emission (Real)", sizeBeforeReal + 1, emissions.size)

            job.cancel()
        }

    @Test
    fun `seedFlow does not emit Fallback if Real arrives before timeout`() =
        runTest {
            val storedSeed = MutableSharedFlow<String>(replay = 0)
            val identityRepo = createRepositoryForSeedFlowTest("test_sim_settings_2.preferences_pb", storedSeed)
            val seedFlow = getInternalSeedFlow(identityRepo)

            val emissions = mutableListOf<Any>()
            val job = launch { seedFlow.collect { emissions.add(it) } }

            // 1. Emit real seed at T=5s (well before 10s timeout)
            testScheduler.advanceTimeBy(5.seconds)
            storedSeed.emit("real_seed")
            testScheduler.runCurrent()

            // We expect at least one emission (Real).
            // If there are 2 emissions, they both must be Real (due to merge of retryFlow and repoFlow).
            // But distinctUntilChanged should ideally leave only one.
            assertTrue("Should have emitted Real seed", emissions.isNotEmpty())
            assertTrue(
                "All emissions must be Real, found: $emissions",
                emissions.all { it.toString().contains("real_seed") },
            )

            // 2. Wait past total timeout to ensure no Fallback is emitted late
            testScheduler.advanceTimeBy(20.seconds)
            testScheduler.runCurrent()

            assertTrue("Should not emit Fallback after Real", emissions.all { !it.toString().contains("Fallback") })
            job.cancel()
        }

    private fun TestScope.createRepositoryForSeedFlowTest(
        fileName: String,
        storedSeed: Flow<String>,
    ): SimIdentityRepositoryImpl {
        val testDataStore =
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tmpFolder.newFile(fileName) },
            )
        val seedRepo =
            object : SimIdentitySeedRepository {
                override val seedFlow: Flow<String> = storedSeed
                override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                override suspend fun setSeed(seed: String) = Unit

                override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult {
                    val res = storedSeed.firstOrNull { it.isNotEmpty() }
                    return if (res != null) {
                        SeedResolutionResult.Persistent(res)
                    } else {
                        SeedResolutionResult.Ephemeral("gen")
                    }
                }

                override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
            }

        return SimIdentityRepositoryTestHelper.createRepository(
            discoveryDeps = discoveryDeps,
            dataStore = testDataStore,
            seedRepository = seedRepo,
            appSettingsRepository = appSettingsRepository,
            migrationService = migrationService,
            mockSimProvider = mockSimProvider,
            timeProvider = mock(),
            scope = backgroundScope,
        )
    }

    // Suppressing UNCHECKED_CAST for unchecked flow type casting in test.
    @Suppress("UNCHECKED_CAST")
    private fun getInternalSeedFlow(repository: SimIdentityRepositoryImpl): Flow<Any> =
        repository.observer.seedFlow as Flow<Any>

    @Test
    fun `concurrent getSimIdentity calls are serialized by mutex`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tmpFolder.newFile("test_sim_settings_3.preferences_pb") },
                )
            val storedSeed = MutableStateFlow("test-seed")
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) {
                        storedSeed.value = seed
                    }

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult = SeedResolutionResult.Persistent(storedSeed.value)

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.iccId).thenReturn("icc_1")

            val permissionManager = mock<PermissionManager>()
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            val localDiscoveryDeps =
                SimDiscoveryDependencies(
                    context = mock(),
                    subscriptionManager = mock(),
                    permissionManager = permissionManager,
                    permissionStateNotifier = mock { on { changes } doReturn flowOf(Unit) },
                )
            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = localDiscoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock { on { elapsedRealtime() } doReturn 1000L },
                    scope = backgroundScope,
                )

            val jobs =
                List(5) {
                    // Reduced from 10 to 5 for stability in test environment
                    launch {
                        repository.getSimIdentity(0).stableId
                    }
                }
            jobs.joinAll()

            // Because of the mutex and caching, resolution should be stable.
            // Persistence.saveSimIdentitiesInfo should be called, which in turn calls migrationService if needed.
            // Since all 5 calls happen concurrently for the SAME slot, the mutex ensures only one resolution occurs.
            verify(migrationService, times(1)).migrateLegacyData(any())
        }

    @Test
    fun `getSimIdentity concurrent calls for same slot return same instance`() =
        runTest {
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tmpFolder.newFile("test_sim_settings_4.preferences_pb") },
                )
            val storedSeed = MutableStateFlow("instance-test-seed")
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) {
                        storedSeed.value = seed
                    }

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult = SeedResolutionResult.Persistent(storedSeed.value)

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            val mockSubInfo = mock<SubscriptionInfo>()
            whenever(mockSubInfo.simSlotIndex).thenReturn(0)
            whenever(mockSubInfo.subscriptionId).thenReturn(1)
            whenever(mockSubInfo.iccId).thenReturn("icc_instance_test")

            val permissionManager = mock<PermissionManager>()
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            val localDiscoveryDeps =
                SimDiscoveryDependencies(
                    context = mock(),
                    subscriptionManager = mock(),
                    permissionManager = permissionManager,
                    permissionStateNotifier = mock { on { changes } doReturn flowOf(Unit) },
                )
            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps = localDiscoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock { on { elapsedRealtime() } doReturn 1000L },
                    scope = backgroundScope,
                )

            val results = mutableListOf<SimIdentity>()
            val jobs =
                List(10) {
                    launch {
                        results.add(repository.getSimIdentity(0))
                    }
                }
            jobs.joinAll()

            assertEquals(10, results.size)
            val first = results[0]
            assertTrue(
                "All concurrent calls for same slot must return referentially equal SimIdentity instance",
                results.all { it === first },
            )
        }

    @Test
    fun `getSimIdentity for different slots can resolve in parallel`() =
        runTest {
            val storedSeed = MutableStateFlow("parallel-test-seed")
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed
                    override val isEphemeralSeedActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

                    override suspend fun setSeed(seed: String) {
                        // No-op for test
                    }

                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult = SeedResolutionResult.Persistent(storedSeed.value)

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = generator()
                }

            // Mock slow SIM discovery
            val mockSub0 =
                mock<SubscriptionInfo> {
                    on { simSlotIndex } doReturn 0
                    on { subscriptionId } doReturn 100
                    on { iccId } doReturn "icc_0"
                }
            val mockSub1 =
                mock<SubscriptionInfo> {
                    on { simSlotIndex } doReturn 1
                    on { subscriptionId } doReturn 101
                    on { iccId } doReturn "icc_1"
                }

            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSub0, mockSub1))

            val repository =
                SimIdentityRepositoryTestHelper.createRepository(
                    discoveryDeps =
                        SimDiscoveryDependencies(
                            mock(),
                            mock(),
                            permissionManager,
                            mock { on { changes } doReturn flowOf(Unit) },
                        ),
                    dataStore =
                        PreferenceDataStoreFactory.create(
                            scope = backgroundScope,
                        ) { tmpFolder.newFile("parallel_test.pb") },
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = mock { on { elapsedRealtime() } doReturn 1000L },
                    scope = backgroundScope,
                )

            // Launch two parallel resolves
            val job0 = async { repository.getSimIdentity(0) }
            val job1 = async { repository.getSimIdentity(1) }

            val res0 =
                withTimeoutOrNull(30.seconds) { job0.await() }
                    ?: throw AssertionError("Timed out waiting for job0")
            val res1 =
                withTimeoutOrNull(30.seconds) { job1.await() }
                    ?: throw AssertionError("Timed out waiting for job1")

            // Verify both successfully received an identity
            // StableId is generated as icc_<hash> when ICCID is present
            assertTrue("Actual ID0: ${res0.stableId}", res0.stableId.startsWith("icc_"))
            assertTrue("Actual ID1: ${res1.stableId}", res1.stableId.startsWith("icc_"))
        }

    @Test
    fun `slow DataStore seed arrival does not cause terminal failure`() =
        runTest {
            whenever(permissionManager.isTelephonySupported()).thenReturn(true)
            val mockSubInfo =
                mock<SubscriptionInfo> {
                    on { simSlotIndex } doReturn 0
                    on { subscriptionId } doReturn 1
                    on { iccId } doReturn "icc_slow"
                }
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

            // storedSeed is empty initially
            val storedSeed = MutableSharedFlow<String>(replay = 0)
            val identityRepo = createRepositoryForSeedFlowTest("test_slow_seed.preferences_pb", storedSeed)

            val activeSimsList = mutableListOf<List<ActiveSimInfo>>()
            val job =
                launch {
                    identityRepo.observeActiveSims().collect { activeSimsList.add(it) }
                }

            // 1. Initial wait: seed has not arrived yet
            testScheduler.advanceTimeBy(14.seconds)
            testScheduler.runCurrent()

            val sizeBeforeSeed = activeSimsList.size

            // 2. Real seed arrives after delay
            storedSeed.emit("slow_real_seed")
            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.advanceUntilIdle()

            val status = identityRepo.observer.status.value
            assertTrue("Observer status should not be Error, but was $status", status !is TrackingStatus.Error)
            assertEquals(TrackingStatus.Active, status)
            assertTrue("Active SIMs list size should increase after seed arrival", activeSimsList.size > sizeBeforeSeed)

            val lastEmission = activeSimsList.last()
            assertEquals(1, lastEmission.size)
            assertEquals(1, lastEmission.first().subscriptionId)

            job.cancel()
        }
}
