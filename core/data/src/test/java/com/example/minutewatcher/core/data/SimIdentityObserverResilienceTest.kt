package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.SeedResolutionResult
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.SimDiscoveryFailureReason
import com.example.minutewatcher.core.model.SimDiscoveryResult
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityObserverResilienceTest {
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

        // Default trigger
        whenever(discovery.observeSubscriptionChanges()).thenReturn(
            flow {
                emit(Unit)
                awaitCancellation()
            },
        )
    }

    @Test
    fun `physicalActiveSims does not die when fetcher returns Failed due to storage error`() =
        runTest {
            observer = createObserver(backgroundScope)

            // Mock fetcher to return Failed (logical failure)
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Failed(SimDiscoveryFailureReason.STORAGE_ERROR),
            )

            val job = launch { observer.activeSimsCache.collect {} }

            // Advance past debounce and first attempt
            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            // After MAX_GENERAL_ATTEMPTS (5), it should set Error status but stay alive
            // Attempts are: initial + 5 retries = 6 total calls
            testScheduler.advanceTimeBy(100_000.milliseconds) // Enough for all backoffs
            testScheduler.runCurrent()

            verify(fetcher, times(6)).fetchActiveSimsWithRetry(any())
            assertTrue(observer.status.value is TrackingStatus.Error)

            // Now make it succeed and trigger manually
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenReturn(
                SimDiscoveryResult.Available(emptyList()),
            )

            observer.recover()
            testScheduler.advanceTimeBy(1000.milliseconds)
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

            // Mock fetcher to throw unexpected exception
            whenever(fetcher.fetchActiveSimsWithRetry(any())).thenAnswer {
                throw IllegalStateException("Something went terribly wrong")
            }

            val job = launch { observer.activeSimsCache.collect {} }

            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            // Catch block should catch it, emit emptyList and continue.
            // Since it was flatMapLatest on discovery.observeSubscriptionChanges(), it will wait for next trigger.

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
            testScheduler.advanceTimeBy(1000.milliseconds)
            testScheduler.runCurrent()

            // Should have called fetcher again
            verify(fetcher, times(2)).fetchActiveSimsWithRetry(any())
            assertEquals(TrackingStatus.Active, observer.status.value)

            job.cancel()
        }
}
