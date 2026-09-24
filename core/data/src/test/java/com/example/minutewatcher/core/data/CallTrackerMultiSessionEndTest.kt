package com.example.minutewatcher.core.data

import android.telephony.TelephonyManager
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerMultiSessionEndTest : CallTrackerTestFixtures() {
    @Test
    fun `onCallEnded with unknown subId propagates storage error for first session and finishes second session`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            val uptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 60000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(now - 120000L))
            whenever(callSessionRepository.getBootTime(2)).thenReturn(flowOf(realtime - 120000L))
            whenever(callSessionRepository.getUptime(2)).thenReturn(flowOf(uptime - 120000L))
            whenever(callSessionRepository.getCallIdentity(2)).thenReturn(flowOf("id_2"))

            whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
            @Suppress("DEPRECATION")
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            whenever(simUsageRepository.incrementMinutesUsedForCall(eq("id_1"), any(), any()))
                .thenAnswer { throw IOException("Storage failure for id_1") }
            whenever(simUsageRepository.incrementMinutesUsedForCall(eq("id_2"), any(), any()))
                .thenReturn(true)

            try {
                callTracker.onCallEnded(-1)
                fail("Expected IOException to be thrown")
            } catch (e: IOException) {
                assertEquals("Storage failure for id_1", e.message)
            }
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(2))
            verify(simUsageRepository).incrementMinutesUsedForCall(eq("id_2"), any(), any())

            verify(callSessionRepository, never()).clearCallSession(eq(1))

            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            verify(dataChangeNotifier, atLeastOnce()).notifyDataChanged()
        }

    @Test
    fun `retry onCallEnded with unknown subId after storage recovery finishes remaining session`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            val uptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 60000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(now - 120000L))
            whenever(callSessionRepository.getBootTime(2)).thenReturn(flowOf(realtime - 120000L))
            whenever(callSessionRepository.getUptime(2)).thenReturn(flowOf(uptime - 120000L))
            whenever(callSessionRepository.getCallIdentity(2)).thenReturn(flowOf("id_2"))

            whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)
            @Suppress("DEPRECATION")
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            whenever(simUsageRepository.incrementMinutesUsedForCall(eq("id_1"), any(), any()))
                .thenAnswer { throw IOException("Storage failure") }
                .thenReturn(true)

            whenever(simUsageRepository.incrementMinutesUsedForCall(eq("id_2"), any(), any()))
                .thenReturn(true)
                .thenReturn(false)

            try {
                callTracker.onCallEnded(-1)
                fail("Expected IOException on first attempt")
            } catch (e: IOException) {
                assertEquals("Storage failure", e.message)
            }
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(2))
            verify(callSessionRepository, never()).clearCallSession(eq(1))

            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(0L))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))

            verify(simUsageRepository, times(2)).incrementMinutesUsedForCall(eq("id_1"), any(), any())
        }
}
