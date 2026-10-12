package com.example.minutewatcher.core.data

import android.telephony.TelephonyManager
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
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
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerEndTest : CallTrackerTestFixtures() {
    @Test
    fun `test call end with same subId clears state`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            // Mock attribution resolution
            val activeSim =
                ActiveSimInfo(
                    identity =
                        SimIdentity(
                            stableId = "id_1",
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.ICCID,
                            isStable = true,
                        ),
                    subscriptionId = 1,
                    slotIndex = 0,
                    iccId = "icc_1",
                    displayName = "SIM 1",
                    carrierName = "Carrier",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
        }

    @Test
    fun `onCallEnded with unknown subId and single active call attributes correctly`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            timeProvider.setTime(500000000L)
            timeProvider.setRealtime(500000000L)
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            // Mock attribution resolution
            val activeSim =
                ActiveSimInfo(
                    identity =
                        SimIdentity(
                            stableId = "id_1",
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.ICCID,
                            isStable = true,
                        ),
                    subscriptionId = 1,
                    slotIndex = 0,
                    iccId = "icc_1",
                    displayName = "SIM 1",
                    carrierName = "Carrier",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
        }

    @Test
    fun `onCallEnded with unknown subId and global IDLE cleans up all`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            // Mock attribution resolution
            val activeSim =
                ActiveSimInfo(
                    identity =
                        SimIdentity(
                            stableId = "id_1",
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.ICCID,
                            isStable = true,
                        ),
                    subscriptionId = 1,
                    slotIndex = 0,
                    iccId = "icc_1",
                    displayName = "SIM 1",
                    carrierName = "Carrier",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(now - 2000L))
            whenever(callSessionRepository.getElapsedRealtime(2)).thenReturn(flowOf(realtime - 2000L))
            whenever(callSessionRepository.getUptime(2)).thenReturn(flowOf(timeProvider.uptimeMillis() - 2000L))
            whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf("id_0"))

            whenever(
                telephonyManager.callStateForSubscription,
            ).thenReturn(TelephonyManager.CALL_STATE_IDLE)
            // @Suppress("DEPRECATION") is required because TelephonyManager.callState is deprecated on API 31+.
            @Suppress("DEPRECATION")
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Both should be closed due to global IDLE safety
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(2))
        }

    @Test
    fun `processCallDurationLocked sets active status on success`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            val startTime = now - 1000L
            val elapsedRealtime = realtime - 1000L
            val uptime = timeProvider.uptimeMillis() - 1000L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(elapsedRealtime))
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
            callTracker.ensureStarted()
            advanceUntilIdle()
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
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange: saved boot time is in the future relative to current (impossible session)
            val now = timeProvider.now()
            val startTime = now - 1000L
            val elapsedRealtime = timeProvider.elapsedRealtime() + 1L // 1ms after current realtime
            val uptime = timeProvider.uptimeMillis() + 1L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(elapsedRealtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Act
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert
            verify(simUsageRepository, never()).incrementMinutesUsedForCall(any(), any(), any())
            // Verify session WAS cleared
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))

            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
            assertEquals("Stale call session from a previous boot for subId 1", status.message)
        }

    @Test
    fun `onCallEnded only clears session if tracking is disabled`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
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
            whenever(callSessionRepository.getElapsedRealtime(subId))
                .thenReturn(
                    flowOf(realtime - 10000L),
                )

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Assert:
            // 1. Session IS cleared
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(subId))
            // 2. Minutes are NOT incremented
            verify(simUsageRepository, never()).incrementMinutesUsedForCall(any(), any(), any())
        }

    @Test
    fun `onCallEnded with unknown subId only clears all sessions if tracking is disabled`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

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
            verify(simUsageRepository, never()).incrementMinutesUsedForCall(any(), any(), any())
        }

    @Test
    fun `endCall propagates session on write failure`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange
            val subId = 1
            val identity = "id_1"
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(currentRealtime - 60000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(currentUptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Simulate write failure in simUsageRepository
            doAnswer { throw IOException("Write failed") }
                .whenever(simUsageRepository)
                .incrementMinutesUsedForCall(eq(identity), any(), any())

            timeProvider.advance(1000)

            // Act
            try {
                callTracker.onCallEnded(subId)
            } catch (e: IOException) {
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
            callTracker.ensureStarted()
            advanceUntilIdle()
            val subId = 1
            val identity = "id_1"
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            // Setup active session data
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(currentRealtime - 60000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(currentUptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Simulate write failure
            doAnswer { throw IOException("Write failed") }
                .whenever(simUsageRepository)
                .incrementMinutesUsedForCall(eq(identity), any(), any())

            // Act & Assert: it should propagate the exception
            val caught =
                try {
                    callTracker.onCallEnded(subId)
                    false
                } catch (e: IOException) {
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
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange
            val subId = 1
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            // Started 13 hours ago (limit is 12h)
            val startTime = now - (Constants.Calls.MAX_CALL_DURATION_MS + 3600000L)
            val elapsedRealtime = realtime - (Constants.Calls.MAX_CALL_DURATION_MS + 3600000L)
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(elapsedRealtime))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(elapsedRealtime))
            // Force attribution failure
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(null))
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(-1)

            timeProvider.advance(1000)

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Assert: verify session WAS cleared
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(subId))
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
            callTracker.ensureStarted()
            advanceUntilIdle()
            val subId = 1
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()

            // Setup active session
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(null))

            // Trigger unexpected exception in endCall -> processCallDurationLocked ->
            // resolveTargetIdentity -> resolveIdentity
            whenever(simIdentityRepository.observeActiveSims()).thenThrow(IllegalStateException("Unexpected crash"))

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Assert
            // 1. Session IS cleared (clearCallSession called)
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(subId))

            // 2. Status is Error
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals("Unexpected crash", (status as TrackingStatus.Error).message)
        }

    @Test
    fun `session data read timeout with clearSession failure sets cleanup error on call end`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange: Trigger timeout
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(MutableSharedFlow())

            // Arrange: clearSession throws exception
            whenever(callSessionRepository.clearCallSession(eq(1)))
                .thenThrow(RuntimeException("DataStore crash"))

            // Act: End call (which fetches call end data)
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert: status should contain the CLEANUP failure message (overwriting timeout)
            val status = callTracker.status.value as TrackingStatus.Error
            assertTrue(
                "Expected status to contain cleanup failure info, but was: ${status.message}",
                status.message?.contains("Session cleanup failed") == true,
            )

            // Verify clearSession WAS called twice (initial + propagation)
            verify(callSessionRepository, times(2)).clearCallSession(eq(1))
        }

    @Test
    fun `onCallEnded with unknown subId sets error status on getActiveCallSubIds timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // 1. Mock timeout (null) for active sessions
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(null)

            // 2. Act: end call with unknown subId
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // 3. Assert: status should be Error
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
            assertEquals("Timeout reading active call sessions", status.message)
        }

    @Test
    fun `onCallEnded handles race where tracking is disabled during execution`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val settingsFlow = MutableStateFlow(AppSettings(callTrackingEnabled = true))
            whenever(appSettingsRepository.getAppSettings()).thenReturn(settingsFlow)

            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // Setup active session
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            // Mock attribution resolution
            val activeSim =
                ActiveSimInfo(
                    identity =
                        SimIdentity(
                            stableId = "id_1",
                            subscriptionId = 1,
                            slotIndex = 0,
                            source = IdentitySource.ICCID,
                            isStable = true,
                        ),
                    subscriptionId = 1,
                    slotIndex = 0,
                    iccId = "icc_1",
                    displayName = "SIM 1",
                    carrierName = "Carrier",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim)))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Disable tracking
            settingsFlow.value = AppSettings(callTrackingEnabled = false)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Verify minutes were NOT incremented
            verify(simUsageRepository, never()).incrementMinutesUsedForCall(any(), any(), any())
            // Verify session was cleared
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
        }

    @Test
    fun `onCallEnded sets Error status and refreshes on IOException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // Setup active session data
            val subId = 1
            val identity = "id_1"
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(currentRealtime - 60000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(currentUptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Simulate write failure
            doAnswer { throw IOException("DataStore failure") }
                .whenever(simUsageRepository)
                .incrementMinutesUsedForCall(eq(identity), any(), any())

            // Act
            try {
                callTracker.onCallEnded(subId)
            } catch (e: IOException) {
                assertTrue("Expected IOException message to match", e.message?.contains("DataStore failure") == true)
            }
            advanceUntilIdle()

            // Verify status is Error
            val status = callTracker.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `idempotent minutes recording upon retry after cleanup failure`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            val subId = 1
            val identity = "id_1"
            val elapsedRealtime = 5000L
            val signature = "1000|5000"
            val durationMinutes = 5

            // Arrange: Setup active session
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(elapsedRealtime))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(2000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Mock successful increment
            whenever(simUsageRepository.incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature)))
                .thenReturn(true)

            // Mock failure during session cleanup
            whenever(callSessionRepository.clearCallSession(eq(subId)))
                .thenAnswer { throw IOException("Cleanup failed") }

            // Act: First attempt
            timeProvider.setRealtime(elapsedRealtime + (durationMinutes * 60000L))
            try {
                callTracker.onCallEnded(subId)
            } catch (e: IOException) {
                assertEquals("Cleanup failed", e.message)
            }
            advanceUntilIdle()

            // Verify increment happened once
            verify(simUsageRepository, times(1))
                .incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature))

            // Act: Second attempt (Retry)
            // Now mock that it was already counted
            whenever(simUsageRepository.incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature)))
                .thenReturn(false)
            // And cleanup now succeeds
            whenever(callSessionRepository.clearCallSession(eq(subId))).thenReturn(Unit)

            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Verify increment called again but with false result (idempotency)
            verify(simUsageRepository, times(2))
                .incrementMinutesUsedForCall(eq(identity), eq(durationMinutes), eq(signature))

            // Verify cleanup eventually succeeded
            verify(callSessionRepository, times(2)).clearCallSession(eq(subId))
        }

    @Test
    fun `onCallEnded with zero duration cleans up and refreshes status to Idle`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // 1. Manually set status to Active to verify it changes to Idle after cleanup
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            callTracker.onCallStarted(1)
            advanceUntilIdle()
            assertEquals(TrackingStatus.Active, callTracker.status.value)

            // 2. Mock zero duration (currentTime == savedBootTime)
            val elapsedRealtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(timeProvider.now()))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(elapsedRealtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis()))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // 3. Act: End call
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // 4. Assert: session cleared and status is Idle (refreshed)
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }

    @Test
    fun `onCallEnded with missing elapsedRealtime cleans up and refreshes status to Idle`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            callTracker.onCallStarted(1)
            advanceUntilIdle()
            assertEquals(TrackingStatus.Active, callTracker.status.value)

            // Mock missing elapsedRealtime (0L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(timeProvider.now()))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(0L))

            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }

    @Test
    fun `onCallEnded with extremely long session sets Error and does NOT refresh to Idle`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // Started 13 hours ago
            val elapsedRealtime = timeProvider.elapsedRealtime() - (Constants.Calls.MAX_CALL_DURATION_MS + 1000L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(timeProvider.now() - 1000L))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(elapsedRealtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis()))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Verify session cleared
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
            // Status remains Error, NOT Idle
            assertTrue(callTracker.status.value is TrackingStatus.Error)
        }
}
