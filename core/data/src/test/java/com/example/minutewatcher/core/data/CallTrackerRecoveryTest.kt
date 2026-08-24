package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerRecoveryTest : CallTrackerTestFixtures() {

    @Test
    fun `onBoot clears all call sessions`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.onBoot()
            advanceUntilIdle()

            verify(callSessionRepository).clearAllCallSessions()
        }

    @Test
    fun `test recover updates status from repository`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))

            // Act
            callTracker.recover()
            advanceUntilIdle()
            testDispatcher.scheduler.advanceUntilIdle()

            // Assert
            assertEquals(
                "Expected status to be Active because there is an active call",
                TrackingStatus.Active,
                callTracker.status.value,
            )

            // And now verify it goes back to Idle if repository is empty
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            callTracker.recover()
            advanceUntilIdle()
            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }

    @Test
    fun `recover clears error status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            // Arrange: Force error status first (attribution failure)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(timeProvider.now() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList<com.example.minutewatcher.core.model.ActiveSimInfo>()))

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Now "fix" the problem and recover
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            // Act
            callTracker.recover()
            advanceUntilIdle()

            // Assert
            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }
}
