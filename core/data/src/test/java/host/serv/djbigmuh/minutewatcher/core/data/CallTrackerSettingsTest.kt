package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

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
            var caughtException: IOException? = null
            try {
                callTracker.onCallStarted(subId = 1)
            } catch (e: IOException) {
                caughtException = e
            }

            advanceUntilIdle()

            // Assert: verify that error status was set and call start state was NOT saved
            assertNotNull("Expected IOException on settings timeout, but none was thrown", caughtException)
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            val errorStatus = status as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, errorStatus.type)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, errorStatus.code)
            verify(callSessionRepository, never()).saveCallSession(eq(1), any(), any(), any(), anyOrNull())
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
                    flow<AppSettings> { throw IOException("Transient error") }
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
            verify(callSessionRepository, atLeastOnce()).saveCallSession(eq(1), any(), any(), any(), anyOrNull())
        }

    @Test
    fun `refreshStatus keeps current status on getActiveCallSubIds timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val tracker = createTracker(backgroundScope)

            // 1. Setup Active status
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            tracker.refreshStatus(force = true)
            assertEquals(TrackingStatus.Active, tracker.status.value)

            // 2. Mock timeout (null)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(null)

            // 3. Perform refresh
            tracker.refreshStatus(force = false)

            // 4. Assert: Status still Active
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
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

            // Act & Assert
            var thrown: CancellationException? = null
            try {
                callTracker.onCallStarted(subId = 1)
            } catch (e: CancellationException) {
                thrown = e
            }

            assertNotNull("CancellationException should be thrown", thrown)
            assertEquals("Intentional cancellation", thrown?.message)
            assertEquals(1, attempts)
        }
}
