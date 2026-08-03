package com.example.minutewatcher.core.data

import android.telephony.TelephonyManager
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()

    private val timeProvider = FixedTimeProvider(currentTime = 1000000L, currentRealtime = 500000L)

    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    private lateinit var callTracker: CallTracker

    @Before
    fun setup() {
        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(callTrackingEnabled = true)))
        whenever(callSessionRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getBootTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getUptime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf(null))

        runTest(testDispatcher) {
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("id_0")
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("id_1")
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
        }
        whenever(simIdentityRepository.getSimOperator(any())).thenReturn(flowOf("MTS"))
        @Suppress("DEPRECATION")
        whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
        whenever(telephonyManager.getCallStateForSubscription()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)

        callTracker =
            CallTracker(
                simIdentityRepository,
                simUsageRepository,
                appSettingsRepository,
                callSessionRepository,
                dataChangeNotifier,
                permissionManager,
                telephonyManager,
                TestScope(testDispatcher),
                timeProvider,
            )
    }

    @Test
    fun `test call start saves state for specific subId`() =
        runTest(testDispatcher) {
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository).setCallStartTime(eq(1), any())
            verify(callSessionRepository).setCallIdentity(eq(1), anyOrNull())
        }

    @Test
    fun `test call end with same subId clears state`() =
        runTest(testDispatcher) {
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(100L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(100L))

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository).setCallStartTime(1, 0)
            verify(callSessionRepository).setBootTime(1, 0)
            verify(callSessionRepository).setUptime(1, 0)
        }

    @Test
    fun `test dual SIM concurrent call tracking`() =
        runTest(testDispatcher) {
            // Start call on subId 1
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()
            verify(callSessionRepository).setCallStartTime(eq(1), any())

            // Start call on subId 2
            callTracker.onCallStarted(subId = 2)
            advanceUntilIdle()
            verify(callSessionRepository).setCallStartTime(eq(2), any())
        }

    @Test
    fun `onBoot clears all call sessions`() =
        runTest(testDispatcher) {
            callTracker.onBoot()
            advanceUntilIdle()

            verify(callSessionRepository).clearAllCallSessions()
        }

    @Test
    fun `onCallEnded with unknown subId and single active call attributes correctly`() =
        runTest(testDispatcher) {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(100L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(100L))

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            verify(callSessionRepository).setCallStartTime(1, 0)
        }

    @Test
    fun `onCallEnded with unknown subId and multiple calls uses default voice attribution`() =
        runTest(testDispatcher) {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(100L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(100L))
            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(2000L))
            whenever(callSessionRepository.getBootTime(2)).thenReturn(flowOf(200L))
            whenever(callSessionRepository.getUptime(2)).thenReturn(flowOf(200L))

            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(2)

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Should attribute to subId 2
            verify(callSessionRepository).setCallStartTime(2, 0)
            verify(callSessionRepository, never()).setCallStartTime(1, 0)
        }

    @Test
    fun `onCallEnded with unknown subId and global IDLE cleans up all`() =
        runTest(testDispatcher) {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(100L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(100L))
            whenever(callSessionRepository.getCallStartTime(2)).thenReturn(flowOf(2000L))
            whenever(callSessionRepository.getBootTime(2)).thenReturn(flowOf(200L))
            whenever(callSessionRepository.getUptime(2)).thenReturn(flowOf(200L))

            whenever(telephonyManager.getCallStateForSubscription()).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Both should be closed due to global IDLE safety
            verify(callSessionRepository).setCallStartTime(1, 0)
            verify(callSessionRepository).setCallStartTime(2, 0)
        }

    @Test
    fun `onCallStarted clears stale session if boot time is invalid`() =
        runTest(testDispatcher) {
            // Arrange: saved boot time is in the future relative to current
            timeProvider.setRealtime(5000L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(10000L))

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert: verify clearSession was called (which sets 0) before starting new
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(1, 0)
            verify(callSessionRepository).setCallStartTime(eq(1), argThat { this > 0 })
        }

    @Test
    fun `onCallStarted sets permission missing status on SecurityException`() =
        runTest(testDispatcher) {
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenThrow(SecurityException())

            callTracker.onCallStarted(-1)
            advanceUntilIdle()

            val status = callTracker.status.first()
            assertTrue((status is TrackingStatus.Error) && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun `onCallEnded sets permission missing status on SecurityException`() =
        runTest(testDispatcher) {
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            whenever(telephonyManager.getCallStateForSubscription()).thenThrow(SecurityException())

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            val status = callTracker.status.first()
            assertTrue((status is TrackingStatus.Error) && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun `processCallDuration sets active status on success`() =
        runTest(testDispatcher) {
            val startTime = 1000L
            val bootTime = 500L
            val uptime = 500L
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
    fun `test recover updates status from repository`() =
        runTest(testDispatcher) {
            // Arrange
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))

            // Act
            callTracker.recover()
            advanceUntilIdle()

            // Assert
            assertEquals(TrackingStatus.Active, callTracker.status.first())

            // And now verify it goes back to Idle if repository is empty
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
            callTracker.recover()
            advanceUntilIdle()
            assertEquals(TrackingStatus.Idle, callTracker.status.first())
        }

    @Test
    fun `attribution failure keeps error status`() =
        runTest(testDispatcher) {
            // Arrange: Force attribution failure in processCallDuration
            val startTime = 1000L
            val bootTime = 500L
            val uptime = 500L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            // No identity found
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(-1)

            timeProvider.advance(10000)

            // Act
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert
            val status = callTracker.status.first()
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
            assertEquals("Attribution failed", status.message)
        }

    @Test
    fun `recover clears error status`() =
        runTest(testDispatcher) {
            // Arrange: Start with an error status
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(500L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(500L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(-1)
            timeProvider.advance(10000)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()
            assertTrue(callTracker.status.value is TrackingStatus.Error)

            // Now "fix" the problem and recover
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            // Act
            callTracker.recover()
            advanceUntilIdle()

            // Assert
            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }

    @Test
    fun `call tracking disabled does not increment minutes`() =
        runTest(testDispatcher) {
            // Arrange
            whenever(
                appSettingsRepository.getAppSettings(),
            ).thenReturn(flowOf(AppSettings(callTrackingEnabled = false)))
            // Re-create tracker to pick up new setting state
            callTracker =
                CallTracker(
                    simIdentityRepository,
                    simUsageRepository,
                    appSettingsRepository,
                    callSessionRepository,
                    dataChangeNotifier,
                    permissionManager,
                    telephonyManager,
                    TestScope(testDispatcher),
                    timeProvider,
                )

            val startTime = 1000L
            val bootTime = 500L
            val uptime = 500L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            timeProvider.advance(10000)

            // Act
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert
            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
        }

    @Test
    fun `stale boot time does not increment minutes`() =
        runTest(testDispatcher) {
            // Arrange: saved boot time is in the future relative to current (impossible session)
            val startTime = 1000L
            val bootTime = 500001L // 1ms after current realtime
            val uptime = 500001L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Act
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert
            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
        }

    @Test
    fun `call duration excludes deep sleep`() =
        runTest(testDispatcher) {
            val startTime = 1000L
            val bootTime = 500L
            val uptime = 500L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(uptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Simulate 100 seconds elapsedRealtime but only 60 seconds uptime (40s deep sleep)
            timeProvider.advance(60000) // now, realtime, uptime all +60s
            timeProvider.advanceRealtimeOnly(40000) // realtime +40s (total 100s since start)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Duration should be 60 seconds (1 minute), not 100 seconds
            verify(simUsageRepository).incrementMinutesUsed(eq("id_0"), eq(1))
        }
}
