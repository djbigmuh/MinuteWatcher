package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.Logger
import com.github.djbigmuh.minutewatcher.core.common.util.resetAppLoggerForTest
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimIdentityObserverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val discovery: SimIdentityDiscovery = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val mockSimProvider: MockSimProvider = mock()
    private val fetcher: ActiveSimsFetcher = mock()
    private val migrationService: IdentityMigrationService = mock()
    private val cache: SimIdentityCache = SimIdentityCache(mock())

    private fun createObserver(scope: CoroutineScope) =
        SimIdentityObserver(
            discovery = discovery,
            seedRepository = seedRepository,
            appSettingsRepository = appSettingsRepository,
            mockSimProvider = mockSimProvider,
            fetcher = fetcher,
            migrationService = migrationService,
            scope = scope,
            cache = cache,
        )

    @Before
    fun setup() =
        runTest(mainDispatcherRule.testDispatcher) {
            whenever(mockSimProvider.getMockSims(any())).thenReturn(null)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
            whenever(seedRepository.seedFlow).thenReturn(MutableStateFlow("test_seed"))
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any()))
                .thenReturn(SeedResolutionResult.Persistent("test_seed"))
            whenever(seedRepository.getOrCreateEphemeralSeed(any()))
                .thenAnswer { (it.arguments[0] as () -> String).invoke() }

            fetcher.stub {
                on { fetchActiveSimsWithRetry(any()) } doReturn SimDiscoveryResult.Available(emptyList())
            }
            whenever(discovery.observeSubscriptionChanges()).thenReturn(
                flow {
                    emit(Unit)
                    awaitCancellation()
                },
            )
        }

    @Test
    fun `circuit breaker triggers after 5 general failures`() =
        runTest {
            val observer = createObserver(backgroundScope)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer { throw IOException("Persistent failure") }

            val job = launch { observer.activeSimsCache.collect {} }

            advanceTimeBy(100_000.milliseconds)
            runCurrent()

            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

            val status = observer.status.value
            assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)

            job.cancel()
        }

    @Test
    fun `circuit breaker resets and restarts on recover`() =
        runTest {
            val observer = createObserver(backgroundScope)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer { throw IOException("Persistent failure") }

            val job = launch { observer.activeSimsCache.collect {} }

            advanceTimeBy(100_000.milliseconds)
            runCurrent()
            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(emptyList()))

            observer.recover()
            advanceTimeBy(10_000.milliseconds)
            runCurrent()

            verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())
            assertEquals(TrackingStatus.Active, observer.status.value)

            job.cancel()
        }

    @Test
    fun `resolvedActiveSimsCache is initially null and transitions to non-null on discovery result`() =
        runTest {
            val observer = createObserver(backgroundScope)
            assertEquals("Initial placeholder value must be null", null, observer.resolvedActiveSimsCache.value)
            assertEquals(
                "activeSimsCache must initially be empty list",
                emptyList<ActiveSimInfo>(),
                observer.activeSimsCache.value,
            )

            val resolvedEmissions = mutableListOf<List<ActiveSimInfo>?>()
            val job = launch { observer.resolvedActiveSimsCache.collect { resolvedEmissions.add(it) } }

            advanceTimeBy(1.seconds)
            runCurrent()

            assertNotNull("Should emit resolved SIMs list", resolvedEmissions.last())
            assertEquals(emptyList<ActiveSimInfo>(), resolvedEmissions.last())
            assertEquals(emptyList<ActiveSimInfo>(), observer.activeSimsCache.value)

            job.cancel()
        }

    @Test
    fun `resolvedActiveSimsCache transitions to list even on permission missing`() =
        runTest {
            val observer = createObserver(backgroundScope)
            fetcher.stub {
                on { fetchActiveSimsWithRetry(any()) } doReturn SimDiscoveryResult.PermissionMissing
            }

            val job = launch { observer.resolvedActiveSimsCache.collect {} }

            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(emptyList<ActiveSimInfo>(), observer.resolvedActiveSimsCache.value)
            assertEquals(emptyList<ActiveSimInfo>(), observer.activeSimsCache.value)

            job.cancel()
        }

    @Test
    fun `seedFlow automatically recovers from Fallback to Real after cooldown`() =
        runTest {
            val seedFlowSource = MutableSharedFlow<String>(replay = 1)
            whenever(seedRepository.seedFlow).thenReturn(seedFlowSource)

            var persistentAvailable = false
            val fakeRepo =
                object : SimIdentitySeedRepository by seedRepository {
                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        if (persistentAvailable) return SeedResolutionResult.Persistent("real")
                        throw IOException()
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = "ephemeral"
                }

            val observer =
                SimIdentityObserver(
                    discovery,
                    fakeRepo,
                    appSettingsRepository,
                    mockSimProvider,
                    fetcher,
                    migrationService,
                    backgroundScope,
                    cache,
                )
            val emissions = mutableListOf<SeedResult>()
            val job = launch { observer.seedFlow.collect { emissions.add(it) } }

            advanceTimeBy(15.seconds)
            runCurrent()
            assertTrue("Should be in Fallback initially", emissions.last() is SeedResult.Fallback)

            persistentAvailable = true
            seedFlowSource.emit("real")
            runCurrent()

            assertTrue("Should be Real after recovery", emissions.last() is SeedResult.Real)
            job.cancel()
        }

    @Test
    fun `migration is retried upon next available emission if previous attempt failed`() =
        runTest {
            val seedFlowSource = MutableStateFlow("")
            whenever(seedRepository.seedFlow).thenReturn(seedFlowSource)

            val fakeRepo =
                object : SimIdentitySeedRepository by seedRepository {
                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        if (seedFlowSource.value.isEmpty()) return SeedResolutionResult.Ephemeral("ephemeral")
                        return SeedResolutionResult.Persistent(seedFlowSource.value)
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = "ephemeral"
                }

            val observer =
                SimIdentityObserver(
                    discovery,
                    fakeRepo,
                    appSettingsRepository,
                    mockSimProvider,
                    fetcher,
                    migrationService,
                    backgroundScope,
                    cache,
                )
            val job = launch { observer.activeSimsCache.collect {} }

            // 1. Ensure Fallback
            advanceTimeBy(15.seconds)
            runCurrent()
            assertTrue(observer.seedFlow.first() is SeedResult.Fallback)

            val sims =
                listOf(
                    ActiveSimInfo(
                        SimIdentity("id", 1, 0, IdentitySource.ICCID, isStable = true),
                        0,
                        1,
                        "icc",
                        "SIM",
                        "Carrier",
                        true,
                        true,
                    ),
                )
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(sims))

            // 2. Mock migration FAILURE
            whenever(migrationService.migrateFromEphemeralSeed(any(), any()))
                .thenThrow(IllegalStateException("FAIL"))

            // 3. Transition to Real
            seedFlowSource.value = "real_seed"
            advanceTimeBy(1.seconds)
            runCurrent()

            // Verify first attempt happened
            verify(migrationService, times(1)).migrateFromEphemeralSeed(eq("ephemeral"), eq(sims))

            // 4. Mock migration SUCCESS
            whenever(migrationService.migrateFromEphemeralSeed(any(), any()))
                .thenReturn(IdentityMigrationService.MigrationOutcome(1, 0, 0))

            // 5. Trigger discovery AGAIN via public recover API
            observer.recover()
            advanceTimeBy(1.seconds)
            runCurrent()

            verify(migrationService, times(2)).migrateFromEphemeralSeed(eq("ephemeral"), eq(sims))

            job.cancel()
        }

    @Test
    fun `recover triggers immediate seed resolution retry`() =
        runTest {
            val seedFlowSource = MutableStateFlow("")
            whenever(seedRepository.seedFlow).thenReturn(seedFlowSource)

            var fail = true
            val fakeRepo =
                object : SimIdentitySeedRepository by seedRepository {
                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        if (fail) throw IOException()
                        return SeedResolutionResult.Persistent("real")
                    }

                    override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = "ephemeral"
                }

            val observer =
                SimIdentityObserver(
                    discovery,
                    fakeRepo,
                    appSettingsRepository,
                    mockSimProvider,
                    fetcher,
                    migrationService,
                    backgroundScope,
                    cache,
                )
            val emissions = mutableListOf<SeedResult>()
            val job = launch { observer.seedFlow.collect { emissions.add(it) } }

            advanceTimeBy(15.seconds)
            runCurrent()
            assertTrue(
                "Initially should be Fallback, was ${emissions.lastOrNull()}",
                emissions.last() is SeedResult.Fallback,
            )

            fail = false
            // Simulation of what recover() does: it might update DataStore
            seedFlowSource.value = "real"
            observer.recover()
            runCurrent()

            assertTrue(
                "Last emission should be Real after recover, was ${emissions.last()}",
                emissions.last() is SeedResult.Real,
            )
            job.cancel()
        }

    @Test
    fun `migration from ephemeral seed does not output secret seed literal to app logger`() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val capturedLogs = mutableListOf<String>()
            val capturingLogger =
                object : Logger {
                    override fun d(
                        tag: String,
                        message: String,
                    ) {
                        capturedLogs.add("$tag: $message")
                    }

                    override fun w(
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        capturedLogs.add("$tag: $message")
                    }

                    override fun e(
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        capturedLogs.add("$tag: $message")
                    }
                }
            AppLogger.init(context, capturingLogger)

            try {
                val secretSeed = "SECRET_EPHEMERAL_SEED_LITERAL_${UUID.randomUUID()}"
                val seedFlowSource = MutableStateFlow("")
                whenever(seedRepository.seedFlow).thenReturn(seedFlowSource)

                val fakeRepo =
                    object : SimIdentitySeedRepository by seedRepository {
                        override suspend fun getOrCreateSeedOrEphemeralFallback(
                            generator: () -> String,
                        ): SeedResolutionResult {
                            if (seedFlowSource.value.isEmpty()) return SeedResolutionResult.Ephemeral(secretSeed)
                            return SeedResolutionResult.Persistent(seedFlowSource.value)
                        }

                        override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = secretSeed
                    }

                val observer =
                    SimIdentityObserver(
                        discovery,
                        fakeRepo,
                        appSettingsRepository,
                        mockSimProvider,
                        fetcher,
                        migrationService,
                        backgroundScope,
                        cache,
                    )

                val sims =
                    listOf(
                        ActiveSimInfo(
                            SimIdentity("id", 1, 0, IdentitySource.ICCID, isStable = true),
                            0,
                            1,
                            "icc",
                            "SIM",
                            "Carrier",
                            true,
                            true,
                        ),
                    )
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(sims))
                whenever(migrationService.migrateFromEphemeralSeed(any(), any()))
                    .thenReturn(IdentityMigrationService.MigrationOutcome(1, 0, 0))

                val job = launch { observer.activeSimsCache.collect {} }

                advanceTimeBy(1.seconds)
                runCurrent()

                seedFlowSource.value = "persistent_real_seed"
                advanceTimeBy(1.seconds)
                runCurrent()

                verify(migrationService).migrateFromEphemeralSeed(eq(secretSeed), eq(sims))

                val containsSecret = capturedLogs.any { it.contains(secretSeed) }
                assertFalse("Secret seed literal must not appear in any log message: $capturedLogs", containsSecret)

                job.cancel()
            } finally {
                resetAppLoggerForTest()
            }
        }

    @Test
    fun `previously known identities in persistence are preserved when permission is missing`() =
        runTest {
            val observer = createObserver(backgroundScope)
            whenever(fetcher.fetchActiveSimsWithRetry(any()))
                .thenReturn(SimDiscoveryResult.PermissionMissing)

            val job = launch { observer.activeSimsCache.collect {} }
            advanceTimeBy(1.seconds)
            runCurrent()

            val status = observer.status.value
            assertTrue(
                "Status must be Error PERMISSION_MISSING",
                status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING,
            )

            verify(migrationService, times(0)).removeOrphanedIdentityKeys(any())

            job.cancel()
        }
}
