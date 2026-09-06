package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimDiscoveryResult
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityObserverTest {
    private val discovery: SimIdentityDiscovery = mock()
    private val seedRepository: SimIdentitySeedRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val mockSimProvider: MockSimProvider = mock()
    private val fetcher: ActiveSimsFetcher = mock()
    private val cache: SimIdentityCache = mock()

    private lateinit var observer: SimIdentityObserver

    private fun createObserver(scope: CoroutineScope): SimIdentityObserver =
        SimIdentityObserver(
            discovery = discovery,
            seedRepository = seedRepository,
            appSettingsRepository = appSettingsRepository,
            mockSimProvider = mockSimProvider,
            fetcher = fetcher,
            scope = scope,
            cache = cache,
        )

    @Before
    fun setup() {
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(seedRepository.seedFlow).thenReturn(MutableStateFlow("test_seed"))
        seedRepository.stub {
            on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn SeedResolutionResult.Persistent("test_seed")
        }
        fetcher.stub {
            on { fetchActiveSimsWithRetry(any()) } doReturn SimDiscoveryResult.Available(emptyList())
        }
        // Use a flow that emits Unit immediately and never finishes
        whenever(discovery.observeSubscriptionChanges()).thenReturn(
            flow {
                emit(Unit)
                awaitCancellation()
            },
        )
    }

    @Test
    fun `circuit breaker triggers after 20 general failures`() =
        runTest {
            observer = createObserver(backgroundScope)
            // Mock fetcher to throw IOException persistently
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer { throw IOException("Persistent failure") }

            val job = launch { observer.activeSimsCache.collect {} }

            // Retries: 1s, 2s, 4s, 8s, 16s... up to 20 times.
            // Total time is about 300 seconds.
            testScheduler.advanceTimeBy(400_000.milliseconds)
            testScheduler.advanceUntilIdle()

            // Verify fetcher was called exactly 21 times
            verify(fetcher, times(21)).fetchActiveSimsWithRetry(any())

            val status = observer.status.value
            assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)
            assertEquals(
                "SIM recovery stopped after 21 consecutive IO failures",
                (status as TrackingStatus.Error).message,
            )

            job.cancel()
        }

    @Test
    fun `circuit breaker resets and restarts on recover`() =
        runTest {
            observer = createObserver(backgroundScope)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer { throw IOException("Persistent failure") }

            val job = launch { observer.activeSimsCache.collect {} }

            // Advance through failure cycle
            testScheduler.advanceTimeBy(400_000.milliseconds)
            testScheduler.advanceUntilIdle()
            verify(fetcher, times(21)).fetchActiveSimsWithRetry(any())

            // Now make fetcher succeed
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(
                    emptyList(),
                ),
            )

            observer.recover()
            // Pass debounce (500ms) and retry logic
            testScheduler.advanceTimeBy(10_000.milliseconds)
            testScheduler.advanceUntilIdle()

            // Total calls should now be 22
            verify(fetcher, times(22)).fetchActiveSimsWithRetry(any())
            assertEquals(TrackingStatus.Active, observer.status.value)

            job.cancel()
        }


    @Test
    fun `use ephemeral seed when seed is unavailable instead of Fallback`() =
        runTest {
            // Arrange
            val seedFlow = MutableStateFlow("") // Initial empty seed
            whenever(seedRepository.seedFlow).thenReturn(seedFlow)

            val hangingRepo =
                object : SimIdentitySeedRepository by seedRepository {
                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(60_000.milliseconds)
                        return SeedResolutionResult.Persistent("too_late")
                    }
                }

            val physicalSims = listOf(
                ActiveSimInfo(
                    identity = SimIdentity("icc_1", 1, 0, IdentitySource.ICCID, isStable = false),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = "icc_1",
                    displayName = "Real SIM",
                    carrierName = "Carrier",
                    mccMnc = "12345",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            )
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(physicalSims))

            observer =
                SimIdentityObserver(
                    discovery = discovery,
                    seedRepository = hangingRepo,
                    appSettingsRepository = appSettingsRepository,
                    mockSimProvider = mockSimProvider,
                    fetcher = fetcher,
                    scope = backgroundScope,
                    cache = cache,
                )
            val job = launch { observer.activeSimsCache.collect {} }

            // Act: Wait for SEED_GENERATION_TIMEOUT_MS (10s) + extra
            testScheduler.advanceTimeBy(20_000.milliseconds)
            testScheduler.advanceUntilIdle()

            // Assert
            val status = observer.status.value
            assertEquals("Status should be Active despite seed delay", TrackingStatus.Active, status)
            assertTrue("SIMs should be discovered using ephemeral seed", observer.activeSimsCache.value.isNotEmpty())
            assertEquals("Real SIM", observer.activeSimsCache.value.first().displayName)

            job.cancel()
        }

    @Test
    fun `seedFlow does not emit Fallback if Real seed arrives before timeout`() =
        runTest {
            val seedFlow = MutableSharedFlow<String>(replay = 1)
            whenever(seedRepository.seedFlow).thenReturn(seedFlow)

            seedRepository.stub {
                on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn SeedResolutionResult.Persistent("too_late")
            }

            observer = createObserver(backgroundScope)

            val emissions = mutableListOf<SeedResult>()
            val job =
                launch {
                    observer.seedFlow.collect { emissions.add(it) }
                }

            testScheduler.advanceTimeBy(5000.milliseconds)
            seedFlow.emit("real_seed")
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(10000.milliseconds)
            testScheduler.advanceUntilIdle()

            assertEquals("Should have only one emission", 1, emissions.size)
            assertTrue("Should be Real emission", emissions[0] is SeedResult.Real)
            assertEquals("real_seed", (emissions[0] as SeedResult.Real).value)

            job.cancel()
        }

    @Test
    fun `seedFlow resets exhausted and emits Real if seed arrives after many failures`() =
        runTest {
            val seedFlow = MutableSharedFlow<String>(replay = 1)
            whenever(seedRepository.seedFlow).thenReturn(seedFlow)

            var shouldFail = true
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).thenAnswer {
                if (shouldFail) {
                    throw IOException("Simulated DataStore error")
                }
                SeedResolutionResult.Persistent("real_seed")
            }

            observer = createObserver(backgroundScope)

            val emissions = mutableListOf<SeedResult>()
            val job =
                launch {
                    observer.seedFlow.collect { emissions.add(it) }
                }

            testScheduler.advanceTimeBy(5000.milliseconds)
            testScheduler.runCurrent()

            seedFlow.emit("late_real_seed")
            testScheduler.runCurrent()

            shouldFail = true
            testScheduler.advanceTimeBy(200_000.milliseconds)
            testScheduler.advanceUntilIdle()

            assertTrue("Should have emitted something", emissions.isNotEmpty())
            assertTrue(emissions.any { it is SeedResult.Real && it.value == "late_real_seed" })

            val lastEmission = emissions.last()
            assertTrue("Last emission should be Real, but was $lastEmission", lastEmission is SeedResult.Real)
            assertEquals("late_real_seed", (lastEmission as SeedResult.Real).value)

            job.cancel()
        }

    @Test
    fun `recover should return early if observation is not initialized`() =
        runTest {
            val testScope = TestScope(testScheduler)
            observer = createObserver(testScope)
            observer.recover()
            observer.recover()
        }

    @Test
    @Suppress("LoopWithTooManyJumpStatements")
    fun `physicalActiveSims does NOT emit emptyList until limit is exceeded after having valid data`() =
        runTest {
            // Тестируем логику retryWhen изолированно
            val emissions = mutableListOf<List<String>>()
            var callCount = 0

            val testFlow =
                flow<List<String>> {
                    while (true) {
                        callCount++
                        if (callCount == 1) {
                            emit(listOf("sim1"))
                            break // Успех
                        } else if (callCount <= 3) {
                            throw IOException("Transient")
                        } else {
                            break
                        }
                    }
                }.retryWhen { cause, attempt ->
                    if (cause !is IOException) return@retryWhen false
                    if (attempt >= 2L) {
                        emit(emptyList()) // Финальная эмиссия
                        false
                    } else {
                        true
                    }
                }

            // 1. Успешный запуск
            val job = launch { testFlow.collect { emissions.add(it) } }
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(listOf("sim1")), emissions)

            // 2. Проверим поведение при ошибке (как будто flow перезапустился во flatMapLatest)
            val failingFlow =
                flow<List<String>> {
                    throw IOException("Failing")
                }.retryWhen { _, attempt ->
                    if (attempt < 2L) {
                        // Имитируем логику: не эмитим ничего
                        true
                    } else {
                        emit(emptyList())
                        false
                    }
                }.catch { /* swallow final exception for verification */ }

            val emissions2 = mutableListOf<List<String>>()
            launch { failingFlow.collect { emissions2.add(it) } }
            testScheduler.advanceUntilIdle()

            assertEquals("Should only contain the final empty list", listOf(emptyList<String>()), emissions2)

            job.cancel()
        }

    @Test
    fun `seedFlow timeout emits ephemeral seed`() =
        runTest {
            val seedFlow = MutableStateFlow("")
            whenever(seedRepository.seedFlow).thenReturn(seedFlow)

            val hangingRepo =
                object : SimIdentitySeedRepository by seedRepository {
                    override suspend fun getOrCreateSeedOrEphemeralFallback(
                        generator: () -> String,
                    ): SeedResolutionResult {
                        delay(20_000.milliseconds)
                        return SeedResolutionResult.Persistent("too_late")
                    }
                }

            observer =
                SimIdentityObserver(
                    discovery = discovery,
                    seedRepository = hangingRepo,
                    appSettingsRepository = appSettingsRepository,
                    mockSimProvider = mockSimProvider,
                    fetcher = fetcher,
                    scope = backgroundScope,
                    cache = cache,
                )

            val emissions = mutableListOf<SeedResult>()
            val job =
                launch {
                    observer.seedFlow.collect { emissions.add(it) }
                }

            // 1. Advance just before SEED_GENERATION_TIMEOUT_MS (10s)
            testScheduler.advanceTimeBy(9_000.milliseconds)
            testScheduler.runCurrent()
            assertEquals("Should not have emitted anything yet", 0, emissions.size)

            // 2. Advance to trigger initial timeout and secondary wait
            testScheduler.advanceTimeBy(10_000.milliseconds) // Total 19s
            testScheduler.runCurrent()

            assertTrue(
                "Should have emitted ephemeral seed after timeout",
                emissions.any { it is SeedResult.Real && !it.isStable },
            )

            job.cancel()
        }

    @Test
    @Suppress("LongMethod")
    fun `activeSimsCache coalesces simultaneous changes of settings and physical sims`() =
        runTest {
            val settingsFlow = MutableStateFlow(AppSettings(benchmarkSimCount = 0))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val physicalSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("icc_1", 1, 0, IdentitySource.ICCID, isStable = true),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "icc_1",
                        displayName = "Real SIM",
                        carrierName = "Carrier",
                        mccMnc = "12345",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                )

            var shouldReturnData = false
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer {
                if (shouldReturnData) {
                    SimDiscoveryResult.Available(physicalSims)
                } else {
                    SimDiscoveryResult.Available(emptyList())
                }
            }

            whenever(mockSimProvider.getMockSims(any())).thenAnswer { invocation ->
                val count = invocation.getArgument<Int>(0)
                if (count > 0) {
                    List(count) { i ->
                        ActiveSimInfo(
                            identity = SimIdentity("mock_$i", i, i, IdentitySource.ICCID, isStable = false),
                            slotIndex = i,
                            subscriptionId = i,
                            iccId = "mock_$i",
                            displayName = "Mock $i",
                            carrierName = "Mock",
                            mccMnc = "00000",
                            isDefaultData = i == 0,
                            isDefaultVoice = i == 0,
                        )
                    }
                } else {
                    null
                }
            }

            observer = createObserver(backgroundScope)
            val emissions = mutableListOf<List<ActiveSimInfo>>()
            val job = launch { observer.activeSimsCache.collect { emissions.add(it) } }

            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()
            assertEquals(1, emissions.size)

            // Simultaneous change
            shouldReturnData = true
            observer.recover()
            settingsFlow.value = AppSettings(benchmarkSimCount = 2)

            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            // In non-benchmark build, physical SIMs should eventually win.
            // We might see 3 emissions: [], [Mock] (while physical is empty), [Real] (after discovery)
            // or 2 emissions if coalesced: [], [Real].
            // The important part is the final state.
            assertTrue(
                "Final emission should contain physical SIMs",
                emissions.last().any { it.displayName == "Real SIM" })
            assertEquals(1, emissions.last().size)

            job.cancel()
        }

    @Test
    fun `activeSimsCache prioritizes physical SIMs over mocks in non-benchmark build`() =
        runTest {
            val settingsFlow = MutableStateFlow(AppSettings(benchmarkSimCount = 2))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val physicalSims = listOf(
                ActiveSimInfo(
                    identity = SimIdentity("real_icc", 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = "real_icc",
                    displayName = "Real SIM",
                    carrierName = "Real Carrier",
                    mccMnc = "12345",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            )

            val mockSims = listOf(
                ActiveSimInfo(
                    identity = SimIdentity("mock_icc", 2, 1, IdentitySource.MOCK, isStable = false),
                    slotIndex = 1,
                    subscriptionId = 2,
                    iccId = "mock_icc",
                    displayName = "Mock SIM",
                    carrierName = "Mock Carrier",
                    mccMnc = "00000",
                    isDefaultData = false,
                    isDefaultVoice = false,
                )
            )

            whenever(mockSimProvider.getMockSims(2)).thenReturn(mockSims)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(physicalSims))

            observer = createObserver(backgroundScope)
            val emissions = mutableListOf<List<ActiveSimInfo>>()
            val job = launch { observer.activeSimsCache.collect { emissions.add(it) } }

            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            // In non-benchmark build (default for tests), physical SIMs should have priority
            assertTrue("Emissions should contain physical SIMs", emissions.last().any { it.displayName == "Real SIM" })
            assertTrue("Emissions should NOT contain mock SIMs", emissions.last().none { it.displayName == "Mock SIM" })

            job.cancel()
        }

    @Test
    fun `activeSimsCache uses mocks when physical SIMs are empty in non-benchmark build`() =
        runTest {
            val settingsFlow = MutableStateFlow(AppSettings(benchmarkSimCount = 2))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val mockSims = listOf(
                ActiveSimInfo(
                    identity = SimIdentity("mock_icc", 2, 1, IdentitySource.MOCK, isStable = false),
                    slotIndex = 1,
                    subscriptionId = 2,
                    iccId = "mock_icc",
                    displayName = "Mock SIM",
                    carrierName = "Mock Carrier",
                    mccMnc = "00000",
                    isDefaultData = false,
                    isDefaultVoice = false,
                )
            )

            whenever(mockSimProvider.getMockSims(2)).thenReturn(mockSims)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(emptyList()))

            observer = createObserver(backgroundScope)
            val emissions = mutableListOf<List<ActiveSimInfo>>()
            val job = launch { observer.activeSimsCache.collect { emissions.add(it) } }

            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            // When physical SIMs are empty, mocks are used as fallback
            assertTrue("Emissions should contain mock SIMs", emissions.last().any { it.displayName == "Mock SIM" })

            job.cancel()
        }

    @Test
    fun `activeSimsCache explicitly prioritizes physical SIMs even if benchmarkSimCount is positive in non-benchmark build`() =
        runTest {
            val settingsFlow = MutableStateFlow(AppSettings(benchmarkSimCount = 5))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val physicalSims = listOf(
                ActiveSimInfo(
                    identity = SimIdentity("real", 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = "real",
                    displayName = "Real SIM",
                    carrierName = "Carrier",
                    mccMnc = "12345",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            )

            val mocks = List(5) { i ->
                ActiveSimInfo(
                    identity = SimIdentity("mock_$i", i + 10, i, IdentitySource.MOCK, isStable = false),
                    slotIndex = i,
                    subscriptionId = i + 10,
                    iccId = "mock_$i",
                    displayName = "Mock $i",
                    carrierName = "Mock",
                    mccMnc = "00000",
                    isDefaultData = i == 0,
                    isDefaultVoice = i == 0,
                )
            }

            whenever(mockSimProvider.getMockSims(5)).thenReturn(mocks)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(SimDiscoveryResult.Available(physicalSims))

            observer = createObserver(backgroundScope)
            val job = launch { observer.activeSimsCache.collect {} }

            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            val currentSims = observer.activeSimsCache.value
            assertEquals("Should contain exactly 1 SIM", 1, currentSims.size)
            assertEquals("Should be Real SIM", "Real SIM", currentSims[0].displayName)

            job.cancel()
        }

    @Test
    fun `seedFlow emits ephemeral Real when internal flow fails terminally`() =
        runTest {
            val failingFlow =
                flow<String> {
                    throw IOException("Terminal failure")
                }
            whenever(seedRepository.seedFlow).thenReturn(failingFlow)
            whenever(seedRepository.getOrCreateSeedOrEphemeralFallback(any())).thenAnswer {
                throw IOException("Initial failure")
            }

            observer = createObserver(backgroundScope)
            val emissions = mutableListOf<SeedResult>()
            val job =
                launch {
                    observer.seedFlow.collect { emissions.add(it) }
                }

            // Act: Wait for all retries to complete (MAX_SEED_RETRIES = 10)
            testScheduler.advanceTimeBy(200_000.milliseconds)
            testScheduler.runCurrent()

            // Assert
            assertTrue(
                "Should have emitted ephemeral Real seed",
                emissions.any { it is SeedResult.Real && !it.isStable })
            job.cancel()
        }
}
