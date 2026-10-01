package com.example.minutewatcher.core.data

import android.telephony.TelephonyManager
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallTrackerAttributionTest : CallTrackerTestFixtures() {
    @Test
    fun `test dual SIM concurrent call tracking`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Start call on subId 1
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()
            verify(callSessionRepository, atLeastOnce()).saveCallSession(eq(1), any(), any(), any(), anyOrNull())

            // Start call on subId 2
            callTracker.onCallStarted(subId = 2)
            advanceUntilIdle()
            verify(callSessionRepository, atLeastOnce()).saveCallSession(eq(2), any(), any(), any(), anyOrNull())
        }

    @Test
    fun `onCallEnded with unknown subId and multiple calls uses default voice attribution`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(now - 2000L))
            whenever(callSessionRepository.getBootTime(2)).thenReturn(flowOf(realtime - 2000L))
            whenever(callSessionRepository.getUptime(2)).thenReturn(flowOf(timeProvider.uptimeMillis() - 2000L))
            whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf("id_0"))

            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(2)

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Heuristic attribution is removed, so NO sessions should be closed
            verify(callSessionRepository, never()).clearCallSession(any())
        }

    @Test
    fun `attribution failure keeps error status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange: Force attribution failure by having TWO active calls and no way to distinguish
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            val startTime = now - 1000L
            val bootTime = realtime - 1000L
            val uptime = timeProvider.uptimeMillis() - 1000L

            // Multiple active calls make heuristic impossible
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            // No identity found in repository
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            // No active SIMs found (or more than one, making it ambiguous)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(-1)

            timeProvider.advance(10000)

            // Act
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, (status as TrackingStatus.Error).type)
            assertEquals("Could not attribute call to SIM identity", status.message)
        }

    @Test
    fun `fallbackAttribution returns null when all reads time out`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: Two active calls
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            // No default voice subId
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(-1)
            // Telephony state OFFHOOK to trigger handleAmbiguousIdle
            // @Suppress("DEPRECATION") is required because callStateForSubscription is deprecated on API 31+.
            @Suppress("DEPRECATION")
            whenever(
                telephonyManager.callStateForSubscription,
            ).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            // Trigger timeout for all reads
            whenever(callSessionRepository.getCallStartTime(any())).thenReturn(MutableSharedFlow())

            // Act
            try {
                callTracker.onCallEnded(-1)
            } catch (e: SecurityException) {
                val matches = e.message?.contains("Permission revoked") == true
                assertTrue("Expected SecurityException message to match", matches)
            }
            advanceUntilIdle()

            // Assert: No minutes incremented
            verify(simUsageRepository, never()).incrementMinutesUsedForCall(any(), any(), any())
            // No calls cleared
            verify(callSessionRepository, never()).clearCallSession(any())
        }

    @Test
    fun `resolveUnknownSubIdEnd handles permission revocation gracefully`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Setup: Active call exists
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))

            // Check permissions succeeds initially in onCallEnded -> executeCallEnded
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.isTelephonySupported()).thenReturn(true)

            // BUT, when resolveUnknownSubIdEnd is called, we simulate SecurityException
            whenever(callSessionRepository.getActiveCallSubIds()).thenThrow(SecurityException("Permission revoked"))

            // Act
            try {
                callTracker.onCallEnded(-1)
            } catch (e: SecurityException) {
                val matches = e.message?.contains("Permission revoked") == true
                assertTrue("Expected SecurityException message to match", matches)
            }
            advanceUntilIdle()

            // Assert: No crash, and status is PERMISSION_MISSING
            val status = callTracker.status.value as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, status.type)
        }

    @Test
    fun `stale session cleanup logs error and does nothing in handleAmbiguousIdle`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: Two active sessions, one is stale (> 12h)
            val staleSubId = 1
            val activeSubId = 2

            val now = 500000000L
            val realtime = 500000000L
            timeProvider.setTime(now)
            timeProvider.setRealtime(realtime)

            // Stale session: started 13 hours ago (by bootTime)
            val staleBootTime = realtime - (Constants.Calls.MAX_CALL_DURATION_MS + 3600000L)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(staleSubId, activeSubId))

            // staleSubId data
            whenever(callSessionRepository.getCallStartTime(staleSubId)).thenReturn(flowOf(now - 13 * 3600000L))
            whenever(callSessionRepository.getBootTime(staleSubId)).thenReturn(flowOf(staleBootTime))
            whenever(callSessionRepository.getUptime(staleSubId)).thenReturn(flowOf(staleBootTime))
            whenever(callSessionRepository.getCallIdentity(staleSubId)).thenReturn(flowOf("id_1"))

            // activeSubId data
            whenever(callSessionRepository.getCallStartTime(activeSubId)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(activeSubId)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(activeSubId))
                .thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(activeSubId)).thenReturn(flowOf("id_2"))

            whenever(
                telephonyManager.callStateForSubscription,
            ).thenReturn(android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)

            // Act: End call with unknown subId
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Assert: verify NO sessions were cleared and NO minutes were added
            verify(simUsageRepository, never()).incrementMinutesUsedForCall(any(), any(), any())
            verify(callSessionRepository, never()).clearCallSession(any())
        }

    @Test
    fun `refreshStatus cleans up attributionFailures for inactive subIds`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val resolver = createAttributionResolver()
            val tracker = createTracker(attributionResolver = resolver)

            // 1. Manually add entries to attributionFailures using public API
            val inactiveSubId = 99
            val activeSubId = 1
            resolver.setFailureCountForTest(inactiveSubId, 1)
            resolver.setFailureCountForTest(activeSubId, 1)

            // 2. Mock active calls to only include activeSubId
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(activeSubId))

            // 3. Call refreshStatus
            tracker.refreshStatus(force = true)

            // 4. Verify inactiveSubId is removed, activeSubId is kept
            assertEquals("Active subId should be kept", 1, resolver.getFailureCount(activeSubId))
            assertEquals("Inactive subId should be removed", 0, resolver.getFailureCount(inactiveSubId))
        }

    @Test
    fun `regression - unknown subId end closes single active session even if data SIM is different`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            // SIM 1 has an active session
            val voiceSubId = 1
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(voiceSubId))
            whenever(callSessionRepository.getCallStartTime(voiceSubId)).thenReturn(flowOf(now - 10000L))
            whenever(callSessionRepository.getBootTime(voiceSubId)).thenReturn(flowOf(realtime - 10000L))
            whenever(callSessionRepository.getUptime(voiceSubId))
                .thenReturn(flowOf(timeProvider.uptimeMillis() - 10000L))
            whenever(callSessionRepository.getCallIdentity(voiceSubId)).thenReturn(flowOf("id_voice"))

            // SIM 2 is data (not active in call)
            whenever(callSessionRepository.getCallIdentity(2)).thenReturn(flowOf("id_data"))

            // Act: Call ended with unknown subId
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Assert: Voice session on SIM 1 is closed
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(voiceSubId))
            // Minutes incremented for SIM 1
            verify(simUsageRepository).incrementMinutesUsedForCall(eq("id_voice"), any(), any())
            // SIM 2 session was NOT attempted to be closed
            verify(callSessionRepository, never()).saveCallSession(eq(2), any(), any(), any(), anyOrNull())
        }

    @Test
    fun `resolveUnknownSubIdEnd attributes end event to single idle sub when other sub throws exception`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))

            // Sub 1: throws IllegalStateException on call state check
            val sub1Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(sub1Manager.callStateForSubscription).thenThrow(IllegalStateException("Telecom error"))

            // Sub 2: returns CALL_STATE_IDLE
            val sub2Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(2)).thenReturn(sub2Manager)
            whenever(sub2Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            // Setup active call data for sub 2
            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(now - 10000L))
            whenever(callSessionRepository.getBootTime(2)).thenReturn(flowOf(realtime - 10000L))
            whenever(callSessionRepository.getUptime(2)).thenReturn(flowOf(timeProvider.uptimeMillis() - 10000L))
            whenever(callSessionRepository.getCallIdentity(2)).thenReturn(flowOf("id_sub2"))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Verify call ended for sub 2 and minutes recorded for id_sub2
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(2))
            verify(simUsageRepository).incrementMinutesUsedForCall(eq("id_sub2"), any(), any())
            verify(callSessionRepository, never()).clearCallSession(eq(1))
        }

    @Test
    fun `resolveUnknownSubIdEnd sets INACCURATE_ATTRIBUTION when all sub call state queries throw exception`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))

            val failingManager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(failingManager)
            whenever(failingManager.callStateForSubscription).thenThrow(IllegalStateException("Telecom error"))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            verify(callSessionRepository, never()).clearCallSession(any())
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `resolveUnknownSubIdEnd attributes to single active sub without query when query throws exception`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            val subId = 1
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId))

            val failingManager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(eq(subId))).thenReturn(failingManager)
            whenever(failingManager.callStateForSubscription).thenThrow(IllegalStateException("Telecom error"))

            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 10000L))
            whenever(callSessionRepository.getBootTime(subId)).thenReturn(flowOf(realtime - 10000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(timeProvider.uptimeMillis() - 10000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf("id_single"))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(subId))
            verify(simUsageRepository).incrementMinutesUsedForCall(eq("id_single"), any(), any())
        }

    @Test
    fun `resolveUnknownSubIdEnd does not treat device as idle when any call state query throws exception`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))

            // Sub 1 returns IDLE, Sub 2 throws exception
            val sub1Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(1)).thenReturn(sub1Manager)
            whenever(sub1Manager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            val sub2Manager: TelephonyManager = mock()
            whenever(telephonyManager.createForSubscriptionId(2)).thenReturn(sub2Manager)
            whenever(sub2Manager.callStateForSubscription).thenThrow(RuntimeException("Service unavailable"))

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 10000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 10000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 10000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_sub1"))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // isDeviceIdle returns false, and Sub 1 is chosen as single IDLE target
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
            verify(callSessionRepository, never()).clearCallSession(eq(2))
        }
}
