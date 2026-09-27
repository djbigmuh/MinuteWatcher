package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimDiscoveryFailureReason
import com.example.minutewatcher.core.model.SimDiscoveryResult
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityObserverResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val discovery: SimIdentityDiscovery = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val mockSimProvider: MockSimProvider = mock()
    private val fetcher: ActiveSimsFetcher = mock()
    private val cache: SimIdentityCache = mock()
    private val migrationService: IdentityMigrationService = mock()

    private lateinit var observer: SimIdentityObserver
    private val triggerFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

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
        initTestLogger()
        whenever(mockSimProvider.getMockSims(any())).thenReturn(null)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(seedRepository.seedFlow).thenReturn(MutableStateFlow("test_seed"))
        seedRepository.stub {
            on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn SeedResolutionResult.Persistent("test_seed")
        }

        // Default trigger
        whenever(discovery.observeSubscriptionChanges()).thenReturn(triggerFlow)
    }

    private suspend fun TestScope.startObserverAndTrigger() {
        testScheduler.runCurrent()
        triggerFlow.emit(Unit)
        testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
        testScheduler.runCurrent()
    }

    @Test
    fun `physicalActiveSims does not die when fetcher returns Failed due to storage error`() =
        runTest {
            observer = createObserver(backgroundScope)
            val job = launch { observer.activeSimsCache.collect {} }

            // Mock fetcher to return Failed (logical failure)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )

            startObserverAndTrigger()

            // After MAX_GENERAL_ATTEMPTS (5), it should set Error status but stay alive
            // Attempts are: initial + 5 retries = 6 total calls
            testScheduler.advanceTimeBy(200_000.milliseconds) // Enough for all backoffs
            testScheduler.runCurrent()

            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())
            assertTrue(observer.status.value is TrackingStatus.Error)

            // Now make it succeed and trigger manually via recover
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(emptyList()),
            )

            observer.recover()
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Total calls should be 6 + 1 = 7
            verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())
            assertEquals(TrackingStatus.Active, observer.status.value)

            job.cancel()
        }

    @Test
    fun `physicalActiveSims does not die when unexpected exception occurs`() =
        runTest {
            observer = createObserver(backgroundScope)
            val job = launch { observer.activeSimsCache.collect {} }

            // Mock fetcher to throw unexpected exception
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer {
                throw IllegalStateException("Something went terribly wrong")
            }

            startObserverAndTrigger()

            verify(fetcher, times(1)).fetchActiveSimsWithRetry(any())
            assertTrue(
                "Status should be Error after unexpected exception",
                observer.status.value is TrackingStatus.Error,
            )
            assertEquals("Something went terribly wrong", (observer.status.value as TrackingStatus.Error).message)

            // Now make it succeed and trigger manually
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(emptyList()),
            )

            observer.recover()
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should have called fetcher again
            verify(fetcher, times(2)).fetchActiveSimsWithRetry(any())
            assertEquals(TrackingStatus.Active, observer.status.value)

            job.cancel()
        }

    @Test
    fun `consecutive failures are reset after success`() =
        runTest {
            observer = createObserver(backgroundScope)
            val job = launch { observer.activeSimsCache.collect {} }

            // 4 failures
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            startObserverAndTrigger()
            testScheduler.advanceTimeBy(20_000.milliseconds)
            testScheduler.runCurrent()

            // 1 success
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(emptyList()),
            )
            triggerFlow.emit(Unit)
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            assertEquals(TrackingStatus.Active, observer.status.value)

            // Another 4 failures - should NOT stop because counter was reset by success
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            triggerFlow.emit(Unit)
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(20_000.milliseconds)
            testScheduler.runCurrent()

            // If counter wasn't reset, it would have stopped (4+2=6 > 5)
            // But it should continue retrying
            assertTrue(
                "Should still be retrying or just failed (not stopped)",
                observer.status.value !is TrackingStatus.Error,
            )

            job.cancel()
        }

    @Test
    fun `auto-recovery restarts discovery after cooldown`() =
        runTest {
            observer = createObserver(backgroundScope)
            val job = launch { observer.activeSimsCache.collect {} }

            // 6 failures in a row (initial + 5 retries) -> Stop
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            startObserverAndTrigger()
            testScheduler.advanceTimeBy(200_000.milliseconds)
            testScheduler.runCurrent()

            assertTrue(observer.status.value is TrackingStatus.Error)
            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

            // Prepare for success
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(emptyList()),
            )

            // Wait for cooldown (15 min)
            testScheduler.advanceTimeBy(15.milliseconds * 60_000)
            testScheduler.runCurrent()
            // Recovery job also goes through debounce
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should have automatically triggered another attempt
            verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())
            assertEquals(TrackingStatus.Active, observer.status.value)

            job.cancel()
        }

    @Test
    fun `only one recovery job is active at a time`() =
        runTest {
            observer = createObserver(backgroundScope)
            val job = launch { observer.activeSimsCache.collect {} }

            // Trigger stop
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            startObserverAndTrigger()
            testScheduler.advanceTimeBy(200_000.milliseconds)
            testScheduler.runCurrent()

            // After 6 failures, recovery should be scheduled
            assertTrue(observer.status.value is TrackingStatus.Error)
            val callsAfterFirstStop = 6
            verify(fetcher, times(callsAfterFirstStop)).fetchActiveSimsWithRetry(any())

            // Advance time half-way through cooldown
            testScheduler.advanceTimeBy(7.milliseconds * 60_000)
            testScheduler.runCurrent()

            // Trigger another "stop" by forcing a retry via another fail
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            observer.recover()
            testScheduler.advanceTimeBy(200_000.milliseconds)
            testScheduler.runCurrent()

            // Should have another 6 failures
            val totalCallsAfterSecondStop = callsAfterFirstStop + 6
            verify(fetcher, times(totalCallsAfterSecondStop)).fetchActiveSimsWithRetry(any())

            // Now prepare for success
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(emptyList()),
            )

            // Advance time past the FIRST cooldown.
            testScheduler.advanceTimeBy(5.milliseconds * 60_000)
            testScheduler.runCurrent()

            // If it triggered, status would be Active. But it should have been replaced.
            assertTrue(
                "Should still be Error because first job was replaced",
                observer.status.value is TrackingStatus.Error,
            )

            // Now advance past second cooldown
            testScheduler.advanceTimeBy(15.milliseconds * 60_000)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Now it should have recovered
            assertEquals(TrackingStatus.Active, observer.status.value)

            // Total calls should be 12 + 1 = 13
            verify(fetcher, times(totalCallsAfterSecondStop + 1)).fetchActiveSimsWithRetry(any())

            job.cancel()
        }

    @Test
    fun `success cancels pending recovery`() =
        runTest {
            observer = createObserver(backgroundScope)
            val job = launch { observer.activeSimsCache.collect {} }

            // Stop
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            startObserverAndTrigger()
            testScheduler.advanceTimeBy(200_000.milliseconds)
            testScheduler.runCurrent()

            // Success from external trigger
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(emptyList()),
            )
            observer.recover()
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            assertEquals(TrackingStatus.Active, observer.status.value)

            // Now wait past the cooldown of the ORIGINAL stop
            testScheduler.advanceTimeBy(20.milliseconds * 60_000)
            testScheduler.runCurrent()

            // Should NOT have triggered another discovery (calls should be 6 + 1 = 7)
            verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())

            job.cancel()
        }

    @Test
    fun `fail-soft keeps last-known-good SIMs during transient failures`() =
        runTest {
            observer = createObserver(backgroundScope)
            val simA = mock<ActiveSimInfo>().apply { whenever(slotIndex).thenReturn(0) }
            val simListA = listOf(simA)

            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(simListA),
            )

            val job = launch { observer.activeSimsCache.collect {} }

            // 1. Success
            startObserverAndTrigger()
            assertEquals(simListA, observer.activeSimsCache.value)

            // 2. Transient failure (4 times)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            triggerFlow.emit(Unit)
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Wait for some retries but not all (4 attempts)
            testScheduler.advanceTimeBy(30_000.milliseconds)
            testScheduler.runCurrent()

            // Should NOT be Error yet (stops at 6)
            assertTrue("Should NOT be Error status yet", observer.status.value !is TrackingStatus.Error)
            // Should keep simListA
            assertEquals("Should keep last known good SIMs during retries", simListA, observer.activeSimsCache.value)

            // 3. Recovery
            val simB = mock<ActiveSimInfo>().apply { whenever(slotIndex).thenReturn(1) }
            val simListB = listOf(simB)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(simListB),
            )

            testScheduler.advanceTimeBy(100_000.milliseconds)
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, observer.status.value)
            assertEquals(simListB, observer.activeSimsCache.value)

            job.cancel()
        }

    @Test
    fun `terminal failure clears SIM list`() =
        runTest {
            observer = createObserver(backgroundScope)
            val simA = mock<ActiveSimInfo>()
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(listOf(simA)),
            )

            val job = launch { observer.activeSimsCache.collect {} }
            startObserverAndTrigger()
            assertEquals(listOf(simA), observer.activeSimsCache.value)

            // 6 failures -> Terminal stop
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            triggerFlow.emit(Unit)
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(500_000.milliseconds)
            testScheduler.runCurrent()

            assertTrue(observer.status.value is TrackingStatus.Error)
            assertTrue("Cache should be empty after terminal failure", observer.activeSimsCache.value.isEmpty())

            job.cancel()
        }

    @Test
    fun `PermissionMissing clears SIM list immediately`() =
        runTest {
            observer = createObserver(backgroundScope)
            val simA = mock<ActiveSimInfo>()
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(listOf(simA)),
            )

            val job = launch { observer.activeSimsCache.collect {} }
            startObserverAndTrigger()
            assertEquals(listOf(simA), observer.activeSimsCache.value)

            // Revoke permission
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.PermissionMissing,
            )
            triggerFlow.emit(Unit)
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertTrue(observer.status.value is TrackingStatus.Error)
            assertTrue("Cache should be empty when permission is missing", observer.activeSimsCache.value.isEmpty())

            job.cancel()
        }

    @Test
    fun `seed change resets last-known-good SIMs`() =
        runTest {
            val seedStateFlow = MutableStateFlow("seed_1")
            whenever(seedRepository.seedFlow).thenReturn(seedStateFlow)
            observer = createObserver(backgroundScope)

            val simA = mock<ActiveSimInfo>()
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(listOf(simA)),
            )

            val job = launch { observer.activeSimsCache.collect {} }
            startObserverAndTrigger()
            assertEquals(listOf(simA), observer.activeSimsCache.value)

            // Seed change
            seedStateFlow.value = "seed_2"
            testScheduler.runCurrent()

            // Now failure
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )
            triggerFlow.emit(Unit)
            testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should be empty
            assertTrue(
                "Cache should be empty for NEW seed cycle during failure",
                observer.activeSimsCache.value.isEmpty(),
            )

            job.cancel()
        }

    @Test
    fun `observer uses single ephemeral seed when storage errors occur`() =
        runTest {
            val ephemeralSeed = "consistent-ephemeral-seed"
            whenever(seedRepository.getOrCreateEphemeralSeed(any())).thenReturn(ephemeralSeed)

            // Case 1: getOrCreateSeedOrEphemeralFallback returns null (e.g. timeout in observer)
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).thenReturn(null)

            // Simulate storage failure in seedFlow to hit the catch block
            val failingSeedFlow = flow<String> { throw IOException("DataStore failed") }
            whenever(seedRepository.seedFlow).thenReturn(failingSeedFlow)

            observer = createObserver(backgroundScope)
            val results = mutableListOf<SeedResult>()
            val job = launch { observer.seedFlow.toList(results) }

            testScheduler.runCurrent()

            assertTrue("Should have fallback result", results.isNotEmpty())
            val lastResult = results.last()
            assertTrue("Result should be Fallback", lastResult is SeedResult.Fallback)
            assertEquals(ephemeralSeed, (lastResult as SeedResult.Fallback).seed)

            // Verify that observer called the repository for ephemeral seed directly
            verify(seedRepository, times(1)).getOrCreateEphemeralSeed(any())

            job.cancel()
        }
}
