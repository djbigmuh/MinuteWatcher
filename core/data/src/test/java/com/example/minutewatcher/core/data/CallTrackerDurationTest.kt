package com.example.minutewatcher.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerDurationTest : CallTrackerTestFixtures() {
    @Test
    fun `call duration includes time in deep sleep`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(currentRealtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(currentUptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            timeProvider.advance(60000) // 60s duration -> 1 min (uptime)
            // Advance 40s realtime only (deep sleep, not counted in uptime)
            timeProvider.advanceRealtimeOnly(40000)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Duration should be 2 minutes (60s + 40s = 100s -> 2 min ceiling)
            verify(simUsageRepository, atLeastOnce()).incrementMinutesUsed(eq("id_0"), eq(2))
        }

    @Test
    fun `onCallEnded ignores negative duration`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(now + 2000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            timeProvider.advance(1000L)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
        }

    @Test
    fun `duration calculation ignores wall clock jumps`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            val uptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Wall clock jump: +1 hour
            timeProvider.setTime(now + 3600000L)
            // Realtime and uptime advance by 60s
            timeProvider.advanceRealtimeOnly(60000L)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Duration should be 1 minute (60s), NOT 60 minutes (1h)
            verify(simUsageRepository, atLeastOnce()).incrementMinutesUsed(eq("id_0"), eq(1))
        }

    @Test
    fun `onCallEnded uses eventElapsedRealtime for duration calculation`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // 1. Start recorded at T=1000ms
            timeProvider.setRealtime(1000L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(500000000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // 2. End event captured at T=61000ms (60s duration), but processed at T=120000ms
            timeProvider.setRealtime(120000L)

            callTracker.onCallEnded(subId = 1, eventElapsedRealtime = 61000L)
            advanceUntilIdle()

            // 3. Duration should be 60s -> 1 minute (NOT 119s -> 2 minutes)
            verify(simUsageRepository).incrementMinutesUsed(eq("id_0"), eq(1))
        }

    @Test
    fun `calculateDurationMinutes rounding 0s to 0m`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis()))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
        }

    @Test
    fun `calculateDurationMinutes rounding 1s to 1m`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis()))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            timeProvider.advance(1000) // 1 second

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(simUsageRepository).incrementMinutesUsed(eq("id_1"), eq(1))
        }

    @Test
    fun `calculateDurationMinutes rounding 59s to 1m`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis()))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            timeProvider.advance(59000) // 59 seconds

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(simUsageRepository).incrementMinutesUsed(eq("id_1"), eq(1))
        }

    @Test
    fun `calculateDurationMinutes rounding 60s to 1m`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis()))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            timeProvider.advance(60000) // 60 seconds

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(simUsageRepository).incrementMinutesUsed(eq("id_1"), eq(1))
        }

    @Test
    fun `calculateDurationMinutes rounding 61s to 2m`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis()))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            timeProvider.advance(61000) // 61 seconds

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(simUsageRepository).incrementMinutesUsed(eq("id_1"), eq(2))
        }
}
