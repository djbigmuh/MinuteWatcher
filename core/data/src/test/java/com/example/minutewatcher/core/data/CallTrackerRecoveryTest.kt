package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerRecoveryTest : CallTrackerTestFixtures() {
    // Override dispatcher to use Unconfined for reliable propagation in complex chains
    override val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun `onBoot clears all call sessions`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker(backgroundScope)
            callTracker.onBoot()
            advanceUntilIdle()

            verify(callSessionRepository).clearAllCallSessions()
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
}
