package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException

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

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var observer: SimIdentityObserver

    @Before
    fun setup() {
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(seedRepository.seedFlow).thenReturn(flowOf("test_seed"))
        whenever(permissionStateNotifier.changes).thenReturn(flowOf(Unit))
        whenever(discovery.observeSubscriptionChanges()).thenReturn(flowOf(Unit))

        observer = SimIdentityObserver(
            discovery = discovery,
            seedRepository = seedRepository,
            appSettingsRepository = appSettingsRepository,
            mockSimProvider = mockSimProvider,
            fetcher = fetcher,
            scope = testScope,
            cache = cache,
            permissionStateNotifier = permissionStateNotifier
        )
    }

    @Test
    fun `circuit breaker triggers after 20 general failures`() = runTest(testDispatcher) {
        // Mock fetcher to throw IOException persistently
        whenever(fetcher.fetchActiveSimsWithRetry(any())).thenThrow(IOException("Persistent failure"))

        val job = launch {
            observer.activeSimsCache.collect {}
        }

        // Advance time to allow for 20 retries with exponential backoff
        // Backoff: 1s, 2s, 4s, 8s, 16s, 16s...
        // Total time for 20 retries is significant, but in virtual time it's fast.
        testScheduler.advanceUntilIdle()

        // Verify fetcher was called exactly 21 times (1 initial + 20 retries)
        // Wait, current logic: if currentAttempt > 20, stop.
        // So attempt 1..20 are retries. Initial is not counted in attempt.incrementAndGet()?
        // Let's check:
        // val currentAttempt = generalAttempt.incrementAndGet()
        // if (currentAttempt > 20) { ... return false }
        // So attempts 1, 2, ..., 20 return true. Attempt 21 returns false.
        // Total calls: 1 (initial) + 20 (retries) = 21.
        verify(fetcher, times(21)).fetchActiveSimsWithRetry(any())

        val status = observer.status.value
        assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)
        assertEquals("SIM discovery stopped after 21 consecutive failures", (status as TrackingStatus.Error).message)

        job.cancel()
    }

    @Test
    fun `circuit breaker resets and restarts on recover`() = runTest(testDispatcher) {
        whenever(fetcher.fetchActiveSimsWithRetry(any())).thenThrow(IOException("Persistent failure"))

        val job = launch {
            observer.activeSimsCache.collect {}
        }
        testScheduler.advanceUntilIdle()
        verify(fetcher, times(21)).fetchActiveSimsWithRetry(any())

        // Now make fetcher succeed
        whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(emptyList())

        observer.recover()
        testScheduler.advanceUntilIdle()

        // Should have called fetcher again (21 previous + 1 new)
        verify(fetcher, times(22)).fetchActiveSimsWithRetry(any())
        assertEquals(TrackingStatus.Active, observer.status.value)

        job.cancel()
    }

    @Test
    fun `security exception limit is respected`() = runTest(testDispatcher) {
        whenever(fetcher.fetchActiveSimsWithRetry(any())).thenThrow(SecurityException("Permission denied"))

        // Mock permission changes to emit Unit when first() is called in retryWhen
        val permissionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)

        // We need to emit Unit repeatedly to satisfy permissionStateNotifier.changes.first()
        val job = launch {
            observer.activeSimsCache.collect {}
        }

        repeat(51) {
            testScheduler.runCurrent()
            permissionChanges.tryEmit(Unit)
        }

        testScheduler.advanceUntilIdle()

        // MAX_SECURITY_ATTEMPTS = 50.
        // Attempt 1..50 return true. Attempt 51 returns false.
        // Total calls = 51.
        verify(fetcher, times(51)).fetchActiveSimsWithRetry(any())

        job.cancel()
    }
}
