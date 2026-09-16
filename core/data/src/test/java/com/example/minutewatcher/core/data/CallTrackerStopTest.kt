package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.lang.reflect.Field
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerStopTest : CallTrackerTestFixtures() {
    @Test
    fun `stop cancels permissionObserverJob`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker(backgroundScope)
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
            val callTracker = createTracker(backgroundScope)
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

    @Test
    fun `stop unblocks awaiters`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val initLock = Mutex(locked = true)
            val tracker = createTracker(
                scope = backgroundScope,
                repo = object : CallSessionRepository by callSessionRepository {
                    override suspend fun getActiveCallSubIds(): List<Int>? {
                        initLock.withLock { return emptyList() }
                    }
                }
            )

            // 1. Trigger start - initJob will hang at initLock
            launch { tracker.ensureStarted() }
            runCurrent()

            // 2. Start a call event that will wait for init
            val callJob = launch { tracker.onCallStarted(1) }
            runCurrent()
            Assert.assertFalse("callJob should be suspended", callJob.isCompleted)

            // 3. Act: stop the tracker while everything is waiting
            tracker.stop()

            // 4. Release the lock to allow subsequent refreshStatus in finalize block to finish
            if (initLock.isLocked) initLock.unlock()
            runCurrent()

            // 5. Assert: callJob should be completed (not hanging)
            val result =
                withTimeoutOrNull(1.seconds) {
                    callJob.join()
                    true
                }
            Assert.assertEquals("onCallStarted should not hang after stop()", true, result)
        }

    @Test
    fun `onCallStarted rethrows CancellationException when caller is cancelled`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val initLock = Mutex(locked = true)
            val tracker = createTracker(
                scope = backgroundScope,
                repo = object : CallSessionRepository by callSessionRepository {
                    override suspend fun getActiveCallSubIds(): List<Int>? {
                        initLock.withLock { return emptyList() }
                    }
                }
            )

            val callerJob =
                launch {
                    tracker.onCallStarted(1)
                }
            runCurrent()
            Assert.assertFalse("Should be suspended waiting for init", callerJob.isCompleted)

            callerJob.cancel()
            runCurrent()

            Assert.assertTrue("Caller job should be cancelled", callerJob.isCancelled)

            if (initLock.isLocked) initLock.unlock()
            tracker.stop()
        }

    @Test
    fun `onCallStarted proceeds when tracker is stopped`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val startHandler: CallStartHandler = mock()
            val tracker =
                CallTracker(
                    callSessionRepository,
                    permissionManager,
                    backgroundScope,
                    createAttributionResolver(),
                    startHandler,
                    mock(),
                    createSessionCleaner(createAttributionResolver()),
                    permissionStateNotifier,
                    createMutexHolder(),
                )

            // 1. Mock stop during onCallStarted
            tracker.stop()

            // 2. Act: call onCallStarted
            tracker.onCallStarted(1)
            advanceUntilIdle()

            // 3. Assert: executeCallStart should be called (not thrown)
            verify(startHandler).executeCallStart(eq(1), eq(-1L), any())
        }

    @Test
    fun `status getter has no side effects`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker(backgroundScope)

            Assert.assertFalse("Tracker should not be started initially", callTracker.isStarted())

            // Act: read status
            val status = callTracker.status.value
            Assert.assertEquals(TrackingStatus.Idle, status)

            // Assert: still not started
            Assert.assertFalse("Tracker should not be started by status getter", callTracker.isStarted())
        }
}
