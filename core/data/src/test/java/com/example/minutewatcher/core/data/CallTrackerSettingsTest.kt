package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerSettingsTest : CallTrackerTestFixtures() {
    @Test
    fun `call tracking sets error on settings timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange: flow that never emits triggers readWithRetry to return failure (on real timeout)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())

            // Act
            callTracker.onCallStarted(subId = 1)
            testScheduler.advanceTimeBy(3000.milliseconds)
            advanceUntilIdle()

            // Assert: verify that error status was set and call start state was NOT saved
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertTrue(
                "Expected timeout message, but was: ${(status as TrackingStatus.Error).message}",
                status.message?.contains("Timeout") == true,
            )
            verify(callSessionRepository, never()).setCallStartTime(eq(1), any())
        }

    @Test
    fun `readWithRetry retries on transient IOException and eventually succeeds`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            var attempts = 0
            whenever(appSettingsRepository.getAppSettings()).thenAnswer {
                attempts++
                if (attempts < 3) {
                    flow<AppSettings> { throw java.io.IOException("Transient error") }
                } else {
                    flowOf(AppSettings(callTrackingEnabled = true))
                }
            }

            callTracker.onCallStarted(subId = 1)

            // Need to advance time for retries
            // delay(500), delay(1000)
            testScheduler.advanceTimeBy(2000.milliseconds)
            advanceUntilIdle()

            assertEquals(3, attempts)
            // Verify it continued to handleCallStarted
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), any())
        }

    @Test(expected = CancellationException::class)
    fun `readWithRetry propagates CancellationException without retry`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            var attempts = 0
            whenever(appSettingsRepository.getAppSettings()).thenAnswer {
                attempts++
                flow<AppSettings> { throw CancellationException("Intentional cancellation") }
            }

            // Act
            callTracker.onCallStarted(subId = 1)
        }
}
