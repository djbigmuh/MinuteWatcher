package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerRecoveryTest : CallTrackerTestFixtures() {
    // Override dispatcher to use Unconfined for reliable propagation in complex chains
    override val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun `onBootAwaited performs selective cleanup`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker(backgroundScope)
            callTracker.onBootAwaited()
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).getActiveCallSubIds()
            verify(callSessionRepository, never()).clearAllCallSessions()
        }

    @Test
    fun `test recover updates status from repository`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker(backgroundScope)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))

            callTracker.recover()
            advanceUntilIdle()

            assertEquals(
                "Expected status to be Active because there is an active call",
                TrackingStatus.Active,
                callTracker.status.value,
            )

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            callTracker.recover()
            advanceUntilIdle()
            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }

    @Test
    fun `recover clears error status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker(backgroundScope)

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(timeProvider.now() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(
                simIdentityRepository.observeActiveSims(),
            ).thenReturn(flowOf(emptyList()))

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            callTracker.recover()
            advanceUntilIdle()

            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }

    @Test
    fun `refreshStatus keeps current status on getActiveCallSubIds timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val mockResolver = mock<CallAttributionResolver>()
            val tracker =
                CallTracker(
                    callSessionRepository,
                    permissionManager,
                    backgroundScope,
                    mockResolver,
                    mock(),
                    mock(),
                    mock(),
                    permissionStateNotifier,
                    createMutexHolder(),
                )

            // 1. Setup Active status
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            tracker.refreshStatus(force = true)
            assertEquals(TrackingStatus.Active, tracker.status.value)
            clearInvocations(mockResolver)

            // 2. Mock timeout (null)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(null)

            // 3. Perform refresh
            tracker.refreshStatus(force = false)

            // 4. Assert: Status still Active, retainOnlyActiveSubIds not called
            assertEquals(TrackingStatus.Active, tracker.status.value)
            verify(mockResolver, never()).retainOnlyActiveSubIds(any())
        }

    @Test
    fun `ensureStarted after failure uses new deferred`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker(backgroundScope)

            // 1. First start fails
            var count = 0
            whenever(callSessionRepository.getActiveCallSubIds()).thenAnswer {
                if (count++ == 0) error("Init failed")
                emptyList<Int>()
            }

            callTracker.ensureStarted()
            advanceUntilIdle()

            // 2. Second start should succeed
            callTracker.ensureStarted()
            advanceUntilIdle()

            assertEquals("Tracker should be initialized on second attempt", true, callTracker.isStarted())

            // 3. Verify that onCallStarted works
            callTracker.onCallStarted(1)
            advanceUntilIdle()
            verify(callSessionRepository, atLeastOnce()).getCallStartTime(any())
        }

    @Test
    fun `repeated call to recover when observer is active does not create a second observation coroutine`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)
            val callTracker = createTracker(backgroundScope)
            callTracker.ensureStarted()
            advanceUntilIdle()

            val job1 = callTracker.permissionObserverJob.get()
            callTracker.recover()
            advanceUntilIdle()
            val job2 = callTracker.permissionObserverJob.get()

            org.junit.Assert.assertSame("Observer coroutine should not be recreated", job1, job2)
        }
}
