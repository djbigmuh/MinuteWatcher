package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun `onCallStarted waits for second initialization when first failed`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("sim_1", 1, 0, IdentitySource.ICCID, isStable = true),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "icc_1",
                        displayName = "SIM 1",
                        carrierName = "C1",
                        mccMnc = "25001",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))

            // 1. Arrange: make permissionStateNotifier.changes throw on first call and return flow on subsequent calls
            whenever(permissionStateNotifier.changes)
                .thenThrow(IllegalStateException("Init 1 failed"))
                .thenAnswer { permissionChangesFlow }

            val callTracker = createTracker()

            // First initialization fails
            callTracker.ensureStarted()
            advanceUntilIdle()

            assertFalse("Tracker should not be started after first failure", callTracker.isStarted())

            // Arrange second attempt to succeed but block on activeCallSubIds
            val initBlockedDeferred = CompletableDeferred<Unit>()
            whenever(callSessionRepository.getActiveCallSubIds()).doSuspendableAnswer {
                withTimeoutOrNull(30.seconds) { initBlockedDeferred.await() }
                    ?: throw AssertionError("Timed out waiting for initBlockedDeferred")
                emptyList<Int>()
            }

            // 2. Call onCallStarted in coroutine (which re-invokes ensureStarted)
            val callJob =
                launch {
                    callTracker.onCallStarted(subId = 1)
                }
            testScheduler.runCurrent()

            // Second initialization started but blocked on initBlockedDeferred
            assertTrue("Tracker should be started/initializing now", callTracker.isStarted())
            verify(callSessionRepository, never()).saveCallSession(eq(1), any(), any(), any(), anyOrNull())

            // 3. Unblock second initialization
            initBlockedDeferred.complete(Unit)
            withTimeoutOrNull(30.seconds) { callJob.join() }
                ?: throw AssertionError("Timed out waiting for callJob")

            // 4. Verification: init completes first, then saveCallSession is invoked
            verify(callSessionRepository, atLeastOnce()).saveCallSession(eq(1), any(), any(), any(), anyOrNull())
        }
}
