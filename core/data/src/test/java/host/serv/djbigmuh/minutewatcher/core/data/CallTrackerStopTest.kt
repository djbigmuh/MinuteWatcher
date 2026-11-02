package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerStopTest : CallTrackerTestFixtures() {
    @Test
    fun `stop halts permission-driven status refresh`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // 1. Initial refresh during start
            verify(callSessionRepository, atLeast(1)).getActiveCallSubIds()

            // 2. Permission change triggers refresh
            permissionChangesFlow.emit(Unit)
            advanceUntilIdle()
            verify(callSessionRepository, times(2)).getActiveCallSubIds()

            // 3. Act: stop the tracker
            callTracker.stop()
            advanceUntilIdle()

            // 4. Permission change after stop should NOT trigger refresh
            permissionChangesFlow.emit(Unit)
            advanceUntilIdle()
            verify(callSessionRepository, times(2)).getActiveCallSubIds() // Still 2
        }

    @Test
    fun `ensureStarted ensures permission observer is active and unique`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            // 1. Double start
            callTracker.ensureStarted()
            callTracker.recover() // Also triggers startPermissionObserver
            advanceUntilIdle()

            // 2. Emit permission change. If unique, only ONE refresh should happen.
            permissionChangesFlow.emit(Unit)
            advanceUntilIdle()

            // Initial refresh from ensureStarted (1) + refresh from recover including stale cleanup (2)
            // + ONE from permission change (1) = 4
            verify(callSessionRepository, times(4)).getActiveCallSubIds()
        }

    @Test
    fun `stop unblocks awaiters`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val initLock = Mutex(locked = true)
            val tracker =
                createTracker(
                    scope = backgroundScope,
                    repo =
                        object : CallSessionRepository by callSessionRepository {
                            override suspend fun getActiveCallSubIds(): List<Int> {
                                initLock.withLock { return emptyList() }
                            }

                            override suspend fun saveCallSession(
                                subId: Int,
                                startTime: Long,
                                elapsedRealtime: Long,
                                uptime: Long,
                                identity: String?,
                            ) {
                                callSessionRepository.saveCallSession(
                                    subId = subId,
                                    startTime = startTime,
                                    elapsedRealtime = elapsedRealtime,
                                    uptime = uptime,
                                    identity = identity,
                                )
                            }

                            override suspend fun clearCallSession(subId: Int) {
                                callSessionRepository.clearCallSession(subId)
                            }
                        },
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
    fun `onCallStarted rethrows CancellationException when caller is canceled`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val initLock = Mutex(locked = true)
            val tracker =
                createTracker(
                    scope = backgroundScope,
                    repo =
                        object : CallSessionRepository by callSessionRepository {
                            override suspend fun getActiveCallSubIds(): List<Int> {
                                initLock.withLock { return emptyList() }
                            }

                            override suspend fun saveCallSession(
                                subId: Int,
                                startTime: Long,
                                elapsedRealtime: Long,
                                uptime: Long,
                                identity: String?,
                            ) {
                                callSessionRepository.saveCallSession(
                                    subId = subId,
                                    startTime = startTime,
                                    elapsedRealtime = elapsedRealtime,
                                    uptime = uptime,
                                    identity = identity,
                                )
                            }

                            override suspend fun clearCallSession(subId: Int) {
                                callSessionRepository.clearCallSession(subId)
                            }
                        },
                )

            val callerJob =
                launch {
                    tracker.onCallStarted(1)
                }
            runCurrent()
            Assert.assertFalse("Should be suspended waiting for init", callerJob.isCompleted)

            callerJob.cancel()
            runCurrent()

            Assert.assertTrue("Caller job should be canceled", callerJob.isCancelled)

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
