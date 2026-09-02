package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    private fun createObserver(scope: kotlinx.coroutines.CoroutineScope): SimIdentityObserver =
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
            on { getOrCreateSeed(any()) } doReturn "test_seed"
        }
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow(replay = 1))
        // Use a flow that emits Unit immediately and never finishes
        whenever(discovery.observeSubscriptionChanges()).thenReturn(
            kotlinx.coroutines.flow.flow {
                emit(Unit)
                kotlinx.coroutines.awaitCancellation()
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
                "SIM discovery stopped after 21 consecutive failures",
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
                com.example.minutewatcher.core.model.SimDiscoveryResult.Available(
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
                com.example.minutewatcher.core.model.SimDiscoveryResult.PermissionMissing,
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
            testScheduler.advanceUntilIdle()

            repeat(51) {
                permissionChanges.emit(Unit)
                testScheduler.advanceTimeBy(3000.milliseconds)
                testScheduler.runCurrent()
            }

            // MAX_SECURITY_ATTEMPTS = 50.
            // Attempt 1..50 return true. Attempt 51 returns false.
            // Total calls = 51.
            verify(fetcher, times(51)).fetchActiveSimsWithRetry(any())

            job.cancel()
        }
}
