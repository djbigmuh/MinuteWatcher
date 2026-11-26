package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SeedResolutionResult
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentitySeedRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryFailureReason
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import kotlin.time.Duration.Companion.seconds

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
    fun `MAX_DISCOVERY_LOOP_ATTEMPTS calls recovery and transitions status to Error UNKNOWN`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer {
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR)
                }

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()

                testScheduler.advanceTimeBy(100_000.milliseconds)
                testScheduler.runCurrent()

                val status = observer.status.value
                assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)
                val error = status as TrackingStatus.Error
                assertEquals(TrackingStatus.ErrorType.UNKNOWN, error.type)
                assertEquals(true, error.message?.startsWith("SIM discovery"))

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )

                testScheduler.advanceTimeBy(15.milliseconds * 60_000)
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                assertEquals(TrackingStatus.Active, observer.status.value)

                job.cancel()
            }
        }

    @Test
    fun `max general attempts on IOException calls recovery and sets status Error UNKNOWN`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer {
                    throw IOException("Disk IO failure")
                }

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()

                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

                val status = observer.status.value
                assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)
                val error = status as TrackingStatus.Error
                assertEquals(TrackingStatus.ErrorType.UNKNOWN, error.type)
                assertEquals(true, error.message?.contains("consecutive IO failures"))

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )

                testScheduler.advanceTimeBy(15.milliseconds * 60_000)
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())
                assertEquals(TrackingStatus.Active, observer.status.value)

                job.cancel()
            }
        }

    @Test
    fun `max general attempts on logical failure emits empty list and schedules recovery`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val simA = createTestActiveSimInfo(slot = 0)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(listOf(simA)),
                )

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()
                assertEquals(listOf(simA), observer.activeSimsCache.value)

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                triggerFlow.emit(Unit)
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(500_000.milliseconds)
                testScheduler.runCurrent()

                assertTrue(observer.status.value is TrackingStatus.Error)
                val error = observer.status.value as TrackingStatus.Error
                assertEquals(TrackingStatus.ErrorType.UNKNOWN, error.type)
                assertTrue("Cache should be empty after terminal failure", observer.activeSimsCache.value.isEmpty())

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(listOf(simA)),
                )
                testScheduler.advanceTimeBy(15.milliseconds * 60_000)
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                assertEquals(TrackingStatus.Active, observer.status.value)
                assertEquals(listOf(simA), observer.activeSimsCache.value)

                job.cancel()
            }
        }

    @Test
    fun `PermissionMissing transitions to Error PERMISSION_MISSING emits empty list and does not schedule recovery`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.PermissionMissing,
                )

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()

                val status = observer.status.value
                assertTrue(status is TrackingStatus.Error)
                val error = status as TrackingStatus.Error
                assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, error.type)
                assertTrue("Cache should be empty when permission missing", observer.activeSimsCache.value.isEmpty())

                verify(fetcher, times(1)).fetchActiveSimsWithRetry(any())
                testScheduler.advanceTimeBy(20.milliseconds * 60_000)
                testScheduler.runCurrent()
                verify(fetcher, times(1)).fetchActiveSimsWithRetry(any())

                job.cancel()
            }
        }

    @Test
    fun `Unsupported transitions to Error NOT_SUPPORTED emits empty list and does not schedule recovery`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Unsupported,
                )

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()

                val status = observer.status.value
                assertTrue(status is TrackingStatus.Error)
                val error = status as TrackingStatus.Error
                assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, error.type)
                assertEquals(TrackingStatus.ErrorCode.SERVICE_UNAVAILABLE, error.code)
                assertTrue("Cache should be empty when unsupported", observer.activeSimsCache.value.isEmpty())

                verify(fetcher, times(1)).fetchActiveSimsWithRetry(any())
                testScheduler.advanceTimeBy(20.milliseconds * 60_000)
                testScheduler.runCurrent()
                verify(fetcher, times(1)).fetchActiveSimsWithRetry(any())

                job.cancel()
            }
        }

    @Test
    fun `scheduleDiscoveryRecovery schedules single attempt and success clears it`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                startObserverAndTrigger()
                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                assertTrue(observer.status.value is TrackingStatus.Error)
                val callsAfterFirstStop = 6
                verify(fetcher, times(callsAfterFirstStop)).fetchActiveSimsWithRetry(any())

                testScheduler.advanceTimeBy(7.milliseconds * 60_000)
                testScheduler.runCurrent()

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                observer.recover()
                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                val callsAfterSecondStop = callsAfterFirstStop + 6
                verify(fetcher, times(callsAfterSecondStop)).fetchActiveSimsWithRetry(any())

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )
                observer.recover()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
                assertEquals(TrackingStatus.Active, observer.status.value)

                testScheduler.advanceTimeBy(20.milliseconds * 60_000)
                testScheduler.runCurrent()

                verify(fetcher, times(callsAfterSecondStop + 1)).fetchActiveSimsWithRetry(any())

                job.cancel()
            }
        }

    @Test
    fun `physicalActiveSims does not die when fetcher returns Failed due to storage error`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )

                startObserverAndTrigger()

                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())
                assertTrue(observer.status.value is TrackingStatus.Error)

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )

                observer.recover()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())
                assertEquals(TrackingStatus.Active, observer.status.value)

                job.cancel()
            }
        }

    @Test
    fun `physicalActiveSims does not die when unexpected exception occurs`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

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

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )

                observer.recover()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                verify(fetcher, times(2)).fetchActiveSimsWithRetry(any())
                assertEquals(TrackingStatus.Active, observer.status.value)

                job.cancel()
            }
        }

    @Test
    fun `consecutive failures are reset after success`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                startObserverAndTrigger()
                testScheduler.advanceTimeBy(20_000.milliseconds)
                testScheduler.runCurrent()

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )
                triggerFlow.emit(Unit)
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
                assertEquals(TrackingStatus.Active, observer.status.value)

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                triggerFlow.emit(Unit)
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                testScheduler.advanceTimeBy(20_000.milliseconds)
                testScheduler.runCurrent()

                assertTrue(
                    "Should still be retrying or just failed (not stopped)",
                    observer.status.value !is TrackingStatus.Error,
                )

                job.cancel()
            }
        }

    @Test
    fun `auto-recovery restarts discovery after cooldown`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                startObserverAndTrigger()
                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                assertTrue(observer.status.value is TrackingStatus.Error)
                verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )

                testScheduler.advanceTimeBy(15.milliseconds * 60_000)
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())
                assertEquals(TrackingStatus.Active, observer.status.value)

                job.cancel()
            }
        }

    @Test
    fun `only one recovery job is active at a time`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                startObserverAndTrigger()
                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                assertTrue(observer.status.value is TrackingStatus.Error)
                val callsAfterFirstStop = 6
                verify(fetcher, times(callsAfterFirstStop)).fetchActiveSimsWithRetry(any())

                testScheduler.advanceTimeBy(7.milliseconds * 60_000)
                testScheduler.runCurrent()

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                observer.recover()
                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                val totalCallsAfterSecondStop = callsAfterFirstStop + 6
                verify(fetcher, times(totalCallsAfterSecondStop)).fetchActiveSimsWithRetry(any())

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )

                testScheduler.advanceTimeBy(5.milliseconds * 60_000)
                testScheduler.runCurrent()

                assertTrue(
                    "Should still be Error because first job was replaced",
                    observer.status.value is TrackingStatus.Error,
                )

                testScheduler.advanceTimeBy(15.milliseconds * 60_000)
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                assertEquals(TrackingStatus.Active, observer.status.value)

                verify(fetcher, times(totalCallsAfterSecondStop + 1)).fetchActiveSimsWithRetry(any())

                job.cancel()
            }
        }

    @Test
    fun `success cancels pending recovery`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                startObserverAndTrigger()
                testScheduler.advanceTimeBy(200_000.milliseconds)
                testScheduler.runCurrent()

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(emptyList()),
                )
                observer.recover()
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
                assertEquals(TrackingStatus.Active, observer.status.value)

                testScheduler.advanceTimeBy(20.milliseconds * 60_000)
                testScheduler.runCurrent()

                verify(fetcher, times(7)).fetchActiveSimsWithRetry(any())

                job.cancel()
            }
        }

    @Test
    fun `fail-soft keeps last-known-good SIMs during transient failures`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val simA = createTestActiveSimInfo(slot = 0)
                val simListA = listOf(simA)

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(simListA),
                )

                val job = launch { observer.activeSimsCache.collect {} }

                startObserverAndTrigger()
                assertEquals(simListA, observer.activeSimsCache.value)

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                triggerFlow.emit(Unit)
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                testScheduler.advanceTimeBy(30_000.milliseconds)
                testScheduler.runCurrent()

                assertTrue("Should NOT be Error status yet", observer.status.value !is TrackingStatus.Error)
                assertEquals(
                    "Should keep last known good SIMs during retries",
                    simListA,
                    observer.activeSimsCache.value,
                )

                val simB = createTestActiveSimInfo(slot = 1)
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
        }

    @Test
    fun `terminal failure clears SIM list`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val simA = createTestActiveSimInfo(slot = 0)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(listOf(simA)),
                )

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()
                assertEquals(listOf(simA), observer.activeSimsCache.value)

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
        }

    @Test
    fun `PermissionMissing clears SIM list immediately`() =
        runTest {
            withTimeout(10.seconds) {
                observer = createObserver(backgroundScope)
                val simA = createTestActiveSimInfo(slot = 0)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(listOf(simA)),
                )

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()
                assertEquals(listOf(simA), observer.activeSimsCache.value)

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
        }

    @Test
    fun `seed change resets last-known-good SIMs`() =
        runTest {
            withTimeout(10.seconds) {
                val seedStateFlow = MutableStateFlow("seed_1")
                whenever(seedRepository.seedFlow).thenReturn(seedStateFlow)
                observer = createObserver(backgroundScope)

                val simA = createTestActiveSimInfo(slot = 0)
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(listOf(simA)),
                )

                val job = launch { observer.activeSimsCache.collect {} }
                startObserverAndTrigger()
                assertEquals(listOf(simA), observer.activeSimsCache.value)

                seedStateFlow.value = "seed_2"
                testScheduler.runCurrent()

                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
                )
                triggerFlow.emit(Unit)
                testScheduler.advanceTimeBy(Constants.Flow.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()

                assertTrue(
                    "Cache should be empty for NEW seed cycle during failure",
                    observer.activeSimsCache.value.isEmpty(),
                )

                job.cancel()
            }
        }

    @Test
    fun `observer uses single ephemeral seed when storage errors occur`() =
        runTest {
            withTimeout(10.seconds) {
                val ephemeralSeed = "consistent-ephemeral-seed"
                whenever(seedRepository.getOrCreateEphemeralSeed(any())).thenReturn(ephemeralSeed)

                whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).thenReturn(null)

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

                verify(seedRepository, times(1)).getOrCreateEphemeralSeed(any())

                job.cancel()
            }
        }

    @Test
    fun `pipeline recovers after failure when observeSubscriptionChanges throws exception`() =
        runTest {
            withTimeout(10.seconds) {
                var callCount = 0
                val mockSim = createTestActiveSimInfo(slot = 0)
                whenever(discovery.observeSubscriptionChanges()).thenAnswer {
                    callCount++
                    if (callCount == 1) {
                        throw IllegalStateException("Observer initialization failed")
                    } else {
                        triggerFlow
                    }
                }
                whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                    SimDiscoveryResult.Available(listOf(mockSim)),
                )

                observer = createObserver(backgroundScope)
                val job = launch { observer.activeSimsCache.collect {} }

                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(1000.milliseconds)
                startObserverAndTrigger()

                assertTrue("Should re-subscribe after exception, callCount=$callCount", callCount > 1)
                assertEquals(listOf(mockSim), observer.activeSimsCache.value)
                assertEquals(TrackingStatus.Active, observer.status.value)

                job.cancel()
            }
        }

    private fun createTestActiveSimInfo(
        slot: Int = 0,
        stableId: String = "sim_$slot",
    ) = ActiveSimInfo(
        identity =
            SimIdentity(
                stableId = stableId,
                subscriptionId = slot + 1,
                slotIndex = slot,
                source = IdentitySource.ICCID,
                isStable = true,
            ),
        slotIndex = slot,
        subscriptionId = slot + 1,
        iccId = "icc_$slot",
        displayName = "SIM ${slot + 1}",
        carrierName = "Carrier",
        isDefaultData = slot == 0,
        isDefaultVoice = slot == 0,
    )
}
