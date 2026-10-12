package com.example.minutewatcher.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallMinutesIdempotencyTest : CallTrackerTestFixtures() {
    @Test
    fun `idempotent minutes recording upon retry after cleanup failure`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            val subId = 1
            val identity = "id_1"
            val startTime = 1000L
            val elapsedRealtime = 5000L
            val signature = "1000|5000"
            val durationMinutes = 5

            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(elapsedRealtime))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(2000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            whenever(simUsageRepository.incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature)))
                .thenReturn(true)

            whenever(callSessionRepository.clearCallSession(eq(subId)))
                .thenAnswer { throw IOException("Cleanup failed") }

            timeProvider.setRealtime(elapsedRealtime + (durationMinutes * 60000L))
            try {
                callTracker.onCallEnded(subId)
            } catch (e: IOException) {
                assertEquals("Cleanup failed", e.message)
            }
            advanceUntilIdle()

            verify(simUsageRepository, times(1))
                .incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature))

            whenever(simUsageRepository.incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature)))
                .thenReturn(false)
            whenever(callSessionRepository.clearCallSession(eq(subId))).thenReturn(Unit)

            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            verify(simUsageRepository, times(2))
                .incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature))

            verify(callSessionRepository, times(2)).clearCallSession(eq(subId))
        }
}
