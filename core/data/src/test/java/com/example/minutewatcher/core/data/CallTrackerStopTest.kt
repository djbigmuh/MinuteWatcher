package com.example.minutewatcher.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerStopTest : CallTrackerTestFixtures() {
    @Test
    fun `stop cancels permissionObserverJob`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            val jobField: Field = CallTracker::class.java.getDeclaredField("permissionObserverJob")
            jobField.isAccessible = true

            val jobBefore = jobField.get(callTracker) as? Job
            Assert.assertNotNull("Job should be started", jobBefore)
            Assert.assertTrue("Job should be active", jobBefore?.isActive == true)

            callTracker.stop()
            advanceUntilIdle()

            Assert.assertNull("Job reference should be cleared", jobField.get(callTracker))
            Assert.assertTrue("Job should be cancelled", jobBefore?.isCancelled == true)
        }

    @Test
    fun `onCallStarted restarts permissionObserverJob`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            val jobField: Field = CallTracker::class.java.getDeclaredField("permissionObserverJob")
            jobField.isAccessible = true

            val jobBefore = jobField.get(callTracker) as? Job
            Assert.assertNotNull(jobBefore)

            // Stop the tracker to reset isInitialized
            callTracker.stop()
            advanceUntilIdle()

            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            val jobAfter = jobField.get(callTracker) as? Job
            Assert.assertNotNull(jobAfter)
            Assert.assertNotSame("Job should be restarted", jobBefore, jobAfter)
            Assert.assertTrue("Old job should be cancelled", jobBefore?.isCancelled == true)
            Assert.assertTrue("New job should be active", jobAfter?.isActive == true)
        }
}
