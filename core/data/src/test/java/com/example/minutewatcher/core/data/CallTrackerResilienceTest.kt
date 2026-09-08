package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerResilienceTest : CallTrackerTestFixtures() {

    @Test
    fun `ensureStarted retries if permissionStateNotifier throws during initialization`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            assertFalse("Initial state should be not started", callTracker.isStarted())

            // 1. Arrange: make permissionStateNotifier.changes throw on first call and succeed on second
            whenever(permissionStateNotifier.changes)
                .thenThrow(RuntimeException("Simulated failure"))
                .thenReturn(permissionChangesFlow)

            // 2. Act: call ensureStarted()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // 3. Assert: isStarted should be false because of the exception, and status should be Error
            assertFalse("Tracker should not be started after failure", callTracker.isStarted())
            assertTrue(
                "Status should be Error",
                callTracker.status.value is TrackingStatus.Error,
            )

            // 4. Act: call ensureStarted() again
            callTracker.ensureStarted()
            advanceUntilIdle()

            // 6. Assert: tracker should be started now
            assertTrue("Tracker should be started after successful retry", callTracker.isStarted())
        }
}
