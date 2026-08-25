package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.IdentitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityRepositoryHangTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var seedRepository: SimIdentitySeedRepository
    private lateinit var permissionStateNotifier: PermissionStateNotifier
    private lateinit var appSettingsRepository: AppSettingsRepository
    private lateinit var permissionManager: PermissionManager
    private lateinit var timeProvider: TimeProvider
    private lateinit var migrationService: IdentityMigrationService
    private lateinit var discoveryDeps: SimDiscoveryDependencies
    private lateinit var testScope: TestScope
    private val testDispatcher = UnconfinedTestDispatcher()
    private val mockSimProvider: MockSimProvider =
        mock {
            on { getMockSims(any()) }.thenReturn(null)
        }

    @Before
    fun setup() {
        testScope = TestScope(testDispatcher)
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
                on { getOrCreateSeed(any()) } doReturn "test_seed"
            }
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))

        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { tmpFolder.newFile("test_sim_settings_${System.currentTimeMillis()}.preferences_pb") },
            )

        discoveryDeps =
            SimDiscoveryDependencies(
                context = context,
                subscriptionManager = subscriptionManager,
                permissionManager = permissionManager,
                permissionStateNotifier = permissionStateNotifier,
            )
    }

    @Test(expected = IllegalStateException::class)
    fun `getSimIdentity throws error when activeSimsCache hangs`() =
        runTest {
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
                )

            // Mock seedRepository.seedFlow to return a flow that never emits,
            // which will cause getSeed() to timeout and return Fallback result,
            // leading to an IllegalStateException.
            whenever(seedRepository.seedFlow).thenReturn(MutableSharedFlow())

            // Act
            identityRepo.getSimIdentity(0)
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

                    override suspend fun setSeed(seed: String) {
                        // No-op
                    }

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        delay(1000.seconds)
                        return "seed"
                    }
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
                )

            // Act: Try to get identity.
            // Using withTimeoutOrNull to ensure we don't hang if implementation is broken.
            val result =
                withTimeoutOrNull(20.seconds) {
                    try {
                        identityRepo.getSimIdentity(0)
                        "success"
                    } catch (_: IllegalStateException) {
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

                    override suspend fun setSeed(seed: String) {
                        // No-op
                    }

                    override suspend fun getOrCreateSeed(generator: () -> String): String {
                        kotlinx.coroutines.delay(1000.seconds)
                        return "seed"
                    }
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = hangingSeedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
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

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository =
                        mock {
                            // Return flow that never emits
                            on { seedFlow } doReturn MutableSharedFlow()
                        },
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
                )

            // Act: wait for internal timeout (SEED_TOTAL_TIMEOUT_MS + 2000ms)
            // SEED_TOTAL_TIMEOUT_MS is 13000ms
            testScheduler.advanceTimeBy(15_100)
            testScheduler.runCurrent()

            // We expect the flow to emit SeedResult.Fallback, which leads to
            // physicalActiveSims emitting, which leads to activeSimsCache emitting.
            // Since activeSimsCache is a StateFlow, it always has an initial value (emptyList).
            // We want to verify that it correctly processed the timeout and is still
            // functional (emitted the result of timeout logic).
            val result = identityRepo.observeActiveSims().first { it.isEmpty() }

            // Assert: should be empty list, not hang
            assertTrue("Result should be empty list after timeout", result.isEmpty())
        }

    @Test
    fun `saveSimIdentitiesInfo does not block other calls on slow migration`() =
        runTest(testDispatcher) {
            val migrationStarted = AtomicBoolean(false)
            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
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
                true
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
        runTest(testDispatcher) {
            // Setup: no permissions yet, so flow won't emit physical SIMs
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
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
        runTest(testDispatcher) {
            val hangingDataStore = mock<DataStore<Preferences>>()
            whenever(hangingDataStore.data).thenReturn(MutableSharedFlow())

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = hangingDataStore,
                    seedRepository = seedRepository,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
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
                        // Намеренно игнорируется, потому что это ожидаемый сбой в тесте на таймаут.
                        // Expected failure
                    }
                }

            // Constants.Flow.LONG_TIMEOUT_MS is 10_000L
            testScheduler.advanceTimeBy(35_000)
            testScheduler.runCurrent()

            assertTrue("Identity resolution should complete even if DataStore hangs (via exception)", job.isCompleted)
        }

    @Test
    fun `seedFlow continues emitting last known value when internal observation hangs`() =
        runTest(testDispatcher) {
            val storedSeed = MutableSharedFlow<String>(replay = 1)
            val seedRepo =
                object : SimIdentitySeedRepository {
                    override val seedFlow: Flow<String> = storedSeed

                    override suspend fun setSeed(seed: String) = storedSeed.emit(seed)

                    override suspend fun getOrCreateSeed(generator: () -> String): String = "generated_seed"
                }

            val identityRepo =
                SimIdentityRepositoryImpl(
                    discoveryDeps = discoveryDeps,
                    dataStore = dataStore,
                    seedRepository = seedRepo,
                    appSettingsRepository = appSettingsRepository,
                    migrationService = migrationService,
                    mockSimProvider = mockSimProvider,
                    timeProvider = timeProvider,
                    scope = testScope,
                )

            // 1. Emit real seed
            storedSeed.emit("seed_1")
            testScheduler.runCurrent()

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
            testScheduler.advanceTimeBy(15_000)
            testScheduler.runCurrent()

            // 4. New resolution attempt. It should still work using the last known seed
            // provided by the replay cache of the shared seedFlow.
            val id2 = identityRepo.getSimIdentity(0)
            assertEquals("Should still use last known identity after hang", id1.stableId, id2.stableId)
        }
}
