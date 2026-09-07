package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.initTestLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerResilienceTest : CallTrackerTestFixtures() {
    @Before
    override fun setup() {
        super.setup()
        initTestLogger()
    }

    @Test
    fun `tracker restarts permission observation after flow exception`() =
        runTest(testDispatcher) {
            var collectionCount = 0
            val crashingFlow =
                flow<Unit> {
                    collectionCount++
                    error("Simulated failure $collectionCount")
                }

            whenever(permissionStateNotifier.changes).thenReturn(crashingFlow)

            val tracker = createTracker()
            tracker.ensureStarted()
            advanceUntilIdle()

            // sequence: 1000, 2000, 4000, 8000 ...
            testScheduler.advanceTimeBy(10000)
            runCurrent()
            advanceUntilIdle()

            // 1 initial + at least 3 retries
            assertTrue("Should have retried several times, got $collectionCount", collectionCount >= 4)
        }

    @Test
    fun `tracker stops permission observation permanently after max retries`() =
        runTest(testDispatcher) {
            var collectionCount = 0
            val crashingFlow =
                flow<Unit> {
                    collectionCount++
                    error("Permanent failure")
                }

            whenever(permissionStateNotifier.changes).thenReturn(crashingFlow)

            val tracker = createTracker()
            tracker.ensureStarted()

            // Advance by enough time to reach 10 retries.
            testScheduler.advanceTimeBy(1000_000)
            runCurrent()
            advanceUntilIdle()

            // 1 initial + 10 retries = 11 attempts
            assertEquals("Should have attempted exactly 11 times", 11, collectionCount)
        }
}
