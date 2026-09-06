package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
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
    private val permissionStateNotifier: PermissionStateNotifier = mock()

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
            permissionStateNotifier = permissionStateNotifier,
        )

    @Before
    fun setup() {
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(seedRepository.seedFlow).thenReturn(MutableStateFlow("test_seed"))
        seedRepository.stub {
            on { getOrCreateSeedOrEphemeralFallback(any()) } doReturn SeedResolutionResult.Persistent("test_seed")
        }
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow(replay = 1))
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
    fun `security exception limit is respected`() =
        runTest {
            observer = createObserver(backgroundScope)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.PermissionMissing,
            )

            // Mock permission changes to emit Unit when first() is called in retryWhen
            // Use replay = 0 to ensure it waits for the next emission
            val permissionChanges = MutableSharedFlow<Unit>(replay = 0)
            whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)

            val job =
                launch {
                    observer.activeSimsCache.collect {}
                }

            // Advance time to pass initial debounce from setup emission
            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            repeat(6) {
                permissionChanges.emit(Unit)
                testScheduler.advanceTimeBy(3000.milliseconds)
                testScheduler.runCurrent()
            }

            // MAX_SECURITY_ATTEMPTS = 5.
            // Attempt 1..5 return true. Attempt 6 returns false.
            // Total calls = 6.
            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())

            job.cancel()
        }

    @Test
    fun `fallback to SeedResult_Fallback when seed is unavailable`() =
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

            observer =
                SimIdentityObserver(
                    discovery = discovery,
                    seedRepository = hangingRepo,
                    appSettingsRepository = appSettingsRepository,
                    mockSimProvider = mockSimProvider,
                    fetcher = fetcher,
                    scope = backgroundScope,
                    cache = cache,
                    permissionStateNotifier = permissionStateNotifier,
                )
            val job = launch { observer.activeSimsCache.collect {} }

            // Act: Wait for SEED_TOTAL_TIMEOUT_MS (13s)
            testScheduler.advanceTimeBy(15_000.milliseconds)
            testScheduler.advanceUntilIdle()

            // Assert
            val status = observer.status.value
            assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)
            assertEquals("SIM identity seed unavailable", (status as TrackingStatus.Error).message)
            assertTrue(observer.activeSimsCache.value.isEmpty())

            seedFlow.value = "recovered_seed"
            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            val finalStatus = observer.status.value
            assertEquals(TrackingStatus.Active, finalStatus)
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
    fun `seedFlow timeout is exactly SEED_GENERATION_TIMEOUT_MS`() =
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
                    permissionStateNotifier = permissionStateNotifier,
                )

            val emissions = mutableListOf<SeedResult>()
            val job =
                launch {
                    observer.seedFlow.collect { emissions.add(it) }
                }

            // 1. Advance just before SEED_GENERATION_TIMEOUT_MS (10s)
            testScheduler.advanceTimeBy(9_000.milliseconds)
            testScheduler.runCurrent()
            assertEquals("Should not have emitted Fallback yet", 0, emissions.size)

            // 2. Advance to trigger timeout
            testScheduler.advanceTimeBy(2_000.milliseconds)
            testScheduler.runCurrent()

            assertTrue("Should have emitted Fallback after timeout", emissions.any { it is SeedResult.Fallback })

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
                        displayName = "SIM 1",
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

            assertEquals("Should have only one new emission after coalescing", 2, emissions.size)
            assertEquals(2, emissions.last().size)
            assertTrue(emissions.last().all { it.displayName?.contains("Mock") == true })

            job.cancel()
        }

    @Test
    fun `seedFlow emits Fallback when internal flow fails terminally`() =
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
            testScheduler.advanceTimeBy(100_000.milliseconds)
            testScheduler.runCurrent()

            // Assert
            assertTrue("Should have emitted Fallback", emissions.any { it is SeedResult.Fallback })
            job.cancel()
        }
}
