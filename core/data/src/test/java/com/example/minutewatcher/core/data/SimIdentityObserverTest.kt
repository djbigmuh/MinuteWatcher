package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimDiscoveryResult
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityObserverTest {
    private val discovery: SimIdentityDiscovery = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val mockSimProvider: MockSimProvider = mock()
    private val fetcher: ActiveSimsFetcher = mock()
    private val cache: SimIdentityCache = mock()
    private val migrationService: IdentityMigrationService = mock()

    private fun createObserver(scope: CoroutineScope): SimIdentityObserver =
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
    fun setup() {
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(seedRepository.seedFlow).thenReturn(MutableStateFlow("test_seed"))
        runBlocking {
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any()))
                .thenReturn(SeedResolutionResult.Persistent("test_seed"))
            whenever(seedRepository.getOrCreateEphemeralSeed(any()))
                .thenAnswer { (it.arguments[0] as () -> String).invoke() }
        }

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
        runTest(UnconfinedTestDispatcher()) {
            val observer = createObserver(backgroundScope)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer { throw IOException("Persistent failure") }

            val job = launch { observer.activeSimsCache.collect {} }

            testScheduler.advanceTimeBy(100_000.milliseconds)
            testScheduler.runCurrent()

            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

            val status = observer.status.value
            assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)

            job.cancel()
        }

    @Test
    fun `circuit breaker resets and restarts on recover`() =
        runTest(UnconfinedTestDispatcher()) {
            val observer = createObserver(backgroundScope)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer { throw IOException("Persistent failure") }

            val job = launch { observer.activeSimsCache.collect {} }

            testScheduler.advanceTimeBy(100_000.milliseconds)
            testScheduler.runCurrent()
            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(emptyList()))

            observer.recover()
            testScheduler.advanceTimeBy(10_000.milliseconds)
            testScheduler.runCurrent()

            verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())
            assertEquals(TrackingStatus.Active, observer.status.value)

            job.cancel()
        }



    @Test
    fun `seedFlow automatically recovers from Fallback to Real after cooldown`() =
        runTest(UnconfinedTestDispatcher()) {
            val seedFlowSource = MutableSharedFlow<String>(replay = 1)
            whenever(seedRepository.seedFlow).thenReturn(seedFlowSource)

            var persistentAvailable = false
            val fakeRepo = object : SimIdentitySeedRepository by seedRepository {
                override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult {
                    if (persistentAvailable) return SeedResolutionResult.Persistent("real")
                    throw IOException()
                }

                override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = "ephemeral"
            }

            val observer = SimIdentityObserver(
                discovery, fakeRepo, appSettingsRepository, mockSimProvider,
                fetcher, migrationService, backgroundScope, cache
            )
            val emissions = mutableListOf<SeedResult>()
            val job = launch { observer.seedFlow.collect { emissions.add(it) } }

            testScheduler.advanceTimeBy(15.seconds)
            assertTrue("Should be in Fallback initially", emissions.last() is SeedResult.Fallback)

            persistentAvailable = true
            seedFlowSource.emit("real")
            testScheduler.advanceUntilIdle()

            assertTrue("Should be Real after recovery", emissions.last() is SeedResult.Real)
            job.cancel()
        }

    @Test
    fun `migration is retried upon next available emission if previous attempt failed`() =
        runTest(UnconfinedTestDispatcher()) {
            val seedFlowSource = MutableStateFlow("")
            whenever(seedRepository.seedFlow).thenReturn(seedFlowSource)

            val fakeRepo = object : SimIdentitySeedRepository by seedRepository {
                override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult {
                    if (seedFlowSource.value.isEmpty()) return SeedResolutionResult.Ephemeral("ephemeral")
                    return SeedResolutionResult.Persistent(seedFlowSource.value)
                }

                override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = "ephemeral"
            }

            val observer = SimIdentityObserver(
                discovery, fakeRepo, appSettingsRepository, mockSimProvider,
                fetcher, migrationService, backgroundScope, cache
            )
            val job = launch { observer.activeSimsCache.collect {} }

            // 1. Ensure Fallback
            testScheduler.advanceTimeBy(15.seconds)
            assertTrue(observer.seedFlow.first() is SeedResult.Fallback)

            val sims = listOf(
                ActiveSimInfo(
                    SimIdentity("id", 1, 0, IdentitySource.ICCID, isStable = true),
                    0, 1, "icc", "SIM", "Carrier", "12345", true, true
                )
            )
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(sims))

            // 2. Mock migration FAILURE
            whenever(migrationService.migrateFromEphemeralSeed(any(), any())).thenThrow(RuntimeException("FAIL"))

            // 3. Transition to Real
            seedFlowSource.value = "real_seed"
            testScheduler.advanceTimeBy(1.seconds) // Wait for debounce and discovery loop
            testScheduler.advanceUntilIdle()

            // Verify first attempt happened
            verify(migrationService, times(1)).migrateFromEphemeralSeed(eq("ephemeral"), eq(sims))

            // 4. Mock migration SUCCESS
            whenever(migrationService.migrateFromEphemeralSeed(any(), any()))
                .thenReturn(IdentityMigrationService.MigrationOutcome(1, 0, 0))

            // 5. Trigger discovery AGAIN
            manualTrigger(observer).emit(Unit)
            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.advanceUntilIdle()

            verify(migrationService, times(2)).migrateFromEphemeralSeed(eq("ephemeral"), eq(sims))

            job.cancel()
        }

    @Test
    fun `recover triggers immediate seed resolution retry`() =
        runTest(UnconfinedTestDispatcher()) {
            val seedFlowSource = MutableStateFlow("")
            whenever(seedRepository.seedFlow).thenReturn(seedFlowSource)

            var fail = true
            val fakeRepo = object : SimIdentitySeedRepository by seedRepository {
                override suspend fun getOrCreateSeedOrEphemeralFallback(generator: () -> String): SeedResolutionResult {
                    if (fail) throw IOException()
                    return SeedResolutionResult.Persistent("real")
                }

                override suspend fun getOrCreateEphemeralSeed(generator: () -> String): String = "ephemeral"
            }

            val observer = SimIdentityObserver(
                discovery, fakeRepo, appSettingsRepository, mockSimProvider,
                fetcher, migrationService, backgroundScope, cache
            )
            val emissions = mutableListOf<SeedResult>()
            val job = launch { observer.seedFlow.collect { emissions.add(it) } }

            testScheduler.advanceTimeBy(15.seconds)
            assertTrue(
                "Initially should be Fallback, was ${emissions.lastOrNull()}",
                emissions.last() is SeedResult.Fallback
            )

            fail = false
            // Simulation of what recover() does: it might update DataStore
            seedFlowSource.value = "real"
            observer.recover()
            testScheduler.advanceUntilIdle()

            assertTrue(
                "Last emission should be Real after recover, was ${emissions.last()}",
                emissions.last() is SeedResult.Real
            )
            job.cancel()
        }

    private fun manualTrigger(observer: SimIdentityObserver): MutableSharedFlow<Unit> {
        val field = SimIdentityObserver::class.java.getDeclaredField("manualTrigger")
        field.isAccessible = true
        return field.get(observer) as MutableSharedFlow<Unit>
    }

    private fun createEmptySnapshot() = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 0L,
        simSnapshots = emptyList(),
        capabilities = emptyList()
    )
}
