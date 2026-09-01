package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerEndTest : CallTrackerTestFixtures() {
    @Test
    fun `test call end with same subId clears state`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
        }

    @Test
    fun `onCallEnded with unknown subId and single active call attributes correctly`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            timeProvider.setTime(500000000L)
            timeProvider.setRealtime(500000000L)
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
        }

    @Test
    fun `onCallEnded with unknown subId and global IDLE cleans up all`() =
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

            whenever(
                telephonyManager.callStateForSubscription,
            ).thenReturn(android.telephony.TelephonyManager.CALL_STATE_IDLE)

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Both should be closed due to global IDLE safety
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(2), eq(0L))
        }

    @Test
    fun `processCallDuration sets active status on success`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            val startTime = now - 1000L
            val bootTime = realtime - 1000L
            val uptime = timeProvider.uptimeMillis() - 1000L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Advance clock so currentBootTime - savedBootTime > 0
            timeProvider.advance(10000)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            val status = callTracker.status.first()
            assertEquals(TrackingStatus.Idle, status)
        }

    @Test
    fun `onCallEnded sets permission missing status on SecurityException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            // Mock both direct call AND permission check to throw
            whenever(permissionManager.hasPhoneStatePermission()).thenThrow(SecurityException())
            whenever(telephonyManager.callStateForSubscription).thenThrow(SecurityException())

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            val status = callTracker.status.first()
            val isError = status is TrackingStatus.Error
            val isPermissionType =
                (status as? TrackingStatus.Error)?.type ==
                    TrackingStatus.ErrorType.PERMISSION_MISSING
            assertTrue(isError && isPermissionType)
        }

    @Test
    fun `stale boot time does not increment minutes`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: saved boot time is in the future relative to current (impossible session)
            val now = timeProvider.now()
            val startTime = now - 1000L
            val bootTime = timeProvider.elapsedRealtime() + 1L // 1ms after current realtime
            val uptime = timeProvider.uptimeMillis() + 1L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Act
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert
            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
            // Verify session WAS cleared
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
        }

    @Test
    fun `call duration includes time in deep sleep`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
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
    fun `onCallEnded only clears session if tracking is disabled`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val subId = 1

            // Tracking is disabled
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(
                    flowOf(
                        AppSettings(
                            callTrackingEnabled = false,
                        ),
                    ),
                )

            // Mock session data exists
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(subId))
                .thenReturn(
                    flowOf(now - 10000L),
                )
            whenever(callSessionRepository.getBootTime(subId))
                .thenReturn(
                    flowOf(realtime - 10000L),
                )

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Assert:
            // 1. Session IS cleared
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(
                eq(subId),
                eq(0L),
            )
            // 2. Minutes are NOT incremented
            verify(simUsageRepository, never()).incrementMinutesUsed(
                any(),
                any(),
            )
        }

    @Test
    fun `onCallEnded with unknown subId only clears all sessions if tracking is disabled`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            // Tracking is disabled
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(
                    flowOf(
                        AppSettings(
                            callTrackingEnabled = false,
                        ),
                    ),
                )

            // Act
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Assert:
            // 1. ALL sessions are cleared
            verify(callSessionRepository).clearAllCallSessions()
            // 2. Minutes are NOT incremented
            verify(simUsageRepository, never()).incrementMinutesUsed(
                any(),
                any(),
            )
        }

    @Test
    fun `endCall propagates session on write failure`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange
            val subId = 1
            val identity = "id_1"
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getBootTime(subId)).thenReturn(flowOf(currentRealtime - 60000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(currentUptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Simulate write failure in simUsageRepository
            org.mockito.kotlin
                .doAnswer { throw java.io.IOException("Write failed") }
                .whenever(simUsageRepository)
                .incrementMinutesUsed(eq(identity), any())

            timeProvider.advance(1000)

            // Act
            try {
                callTracker.onCallEnded(subId)
            } catch (e: java.io.IOException) {
                assertTrue("Expected IOException message to match", e.message?.contains("Write failed") == true)
            }
            advanceUntilIdle()

            // Assert: verify status is Error
            val status = callTracker.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `write failure propagates IOException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val subId = 1
            val identity = "id_1"
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            // Setup active session data
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getBootTime(subId)).thenReturn(flowOf(currentRealtime - 60000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(currentUptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Simulate write failure
            org.mockito.kotlin
                .doAnswer { throw java.io.IOException("Write failed") }
                .whenever(simUsageRepository)
                .incrementMinutesUsed(eq(identity), any())

            // Act & Assert: it should propagate the exception
            val caught =
                try {
                    callTracker.onCallEnded(subId)
                    false
                } catch (e: java.io.IOException) {
                    assertTrue("Expected IOException message to match", e.message?.contains("Write failed") == true)
                    true
                }
            assertTrue("IOException should be propagated", caught)
        }

    @Test
    fun `endCall sets Error on extremely long session and clears it`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange
            val subId = 1
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            // Started 13 hours ago (limit is 12h)
            val startTime = now - (Constants.Calls.MAX_CALL_DURATION_MS + 3600000L)
            val bootTime = realtime - (Constants.Calls.MAX_CALL_DURATION_MS + 3600000L)
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(subId)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(bootTime))
            // Force attribution failure
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(null))
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(-1)

            timeProvider.advance(1000)

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Assert: verify session WAS cleared
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(subId), eq(0L))
            // Status should be Error
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals("Call duration exceeded max limit for subId 1", (status as TrackingStatus.Error).message)
        }

    @Test
    fun `onCallEnded clears session and sets Error on unexpected RuntimeException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val subId = 1
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            // Setup active session
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(subId)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(null))

            // Trigger unexpected exception in endCall -> processCallDuration ->
            // resolveTargetIdentity -> resolveIdentity
            whenever(simIdentityRepository.observeActiveSims()).thenThrow(IllegalStateException("Unexpected crash"))

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Assert
            // 1. Session IS cleared (setCallStartTime called with 0)
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(subId), eq(0L))

            // 2. Status is Error
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals("Unexpected crash", (status as TrackingStatus.Error).message)
        }

    @Test
    fun `loadCallSessionData handle timeout with clearSession failure sets cleanup error`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: Trigger timeout
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(MutableSharedFlow())

            // Arrange: clearSession (setCallStartTime) throws exception
            whenever(callSessionRepository.setCallStartTime(eq(1), any()))
                .thenThrow(RuntimeException("DataStore crash"))

            // Act: End call (which calls loadCallSessionData)
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert: status should contain the CLEANUP failure message (overwriting timeout)
            val status = callTracker.status.value as TrackingStatus.Error
            assertTrue(
                "Expected status to contain cleanup failure info, but was: ${status.message}",
                status.message?.contains("Session cleanup failed") == true,
            )

            // Verify clearSession (setCallStartTime) WAS called twice (initial + propagation)
            verify(callSessionRepository, times(2)).setCallStartTime(eq(1), eq(0L))
        }

    @Test
    fun `calculateDurationMinutes rounding 0s to 0m`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
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

    @Test
    fun `onCallEnded handles race where tracking is disabled during execution`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val settingsFlow = MutableStateFlow(AppSettings(callTrackingEnabled = true))
            whenever(appSettingsRepository.getAppSettings()).thenReturn(settingsFlow)

            val callTracker = createTracker()

            // Setup active session
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Disable tracking
            settingsFlow.value = AppSettings(callTrackingEnabled = false)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Verify minutes were NOT incremented
            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
            // Verify session was cleared
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
        }

    @Test
    fun `onCallEnded sets Error status and refreshes on IOException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            // Setup active session data
            val subId = 1
            val identity = "id_1"
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getBootTime(subId)).thenReturn(flowOf(currentRealtime - 60000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(currentUptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Simulate write failure
            org.mockito.kotlin
                .doAnswer { throw java.io.IOException("DataStore failure") }
                .whenever(simUsageRepository)
                .incrementMinutesUsed(eq(identity), any())

            // Act
            try {
                callTracker.onCallEnded(subId)
            } catch (e: java.io.IOException) {
                assertTrue("Expected IOException message to match", e.message?.contains("DataStore failure") == true)
            }
            advanceUntilIdle()

            // Verify status is Error
            val status = callTracker.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
        }

    @Test(expected = IllegalStateException::class)
    fun `endCall throws IllegalStateException when called without mutex`() =
        runTest {
            setupCommonMocks()
            val callTracker = createTracker()
            // Directly call the internal method
            callTracker.endCall(1, true)
        }
}
