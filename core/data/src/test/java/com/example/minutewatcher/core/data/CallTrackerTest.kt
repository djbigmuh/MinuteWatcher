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
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
// Подавление LargeClass оправдано для тестового класса: CallTrackerTest объединяет все сценарии
// тестирования трекинга звонков, включая dual-SIM, сбои DataStore и системные тайминги.
// Разделение теста усложнит поддержку общих моков и фикстур времени.
@Suppress("LargeClass")
class CallTrackerTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()

    private val timeProvider =
        FixedTimeProvider(
            currentTime = 500000000L,
            currentRealtime = 500000000L,
            currentUptime = 500000000L,
        )

    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    @Before
    fun setup() {
        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings(callTrackingEnabled = true)))
        whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf(null))
        whenever(callSessionRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getBootTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getUptime(any())).thenReturn(flowOf(0L))

        whenever(simIdentityRepository.getSimOperator(any())).thenReturn(flowOf("MTS"))
        @Suppress("DEPRECATION") // Testing legacy API path for API < 31
        whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
        whenever(telephonyManager.getCallStateForSubscription()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
    }

    private fun createTracker() =
        CallTracker(
            simIdentityRepository,
            simUsageRepository,
            appSettingsRepository,
            callSessionRepository,
            dataChangeNotifier,
            permissionManager,
            telephonyManager,
            timeProvider,
        )

    private suspend fun setupCommonMocks() {
        whenever(simIdentityRepository.getIdentity(0)).thenReturn("id_0")
        whenever(simIdentityRepository.getIdentity(1)).thenReturn("id_1")
        whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

        val mockSubInfo = mock<android.telephony.SubscriptionInfo>()
        whenever(mockSubInfo.subscriptionId).thenReturn(1)
        whenever(mockSubInfo.simSlotIndex).thenReturn(0)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))
    }

    @Test
    fun `test call start saves state for specific subId`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), any())
        }

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
    fun `test dual SIM concurrent call tracking`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Start call on subId 1
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), any())

            // Start call on subId 2
            callTracker.onCallStarted(subId = 2)
            advanceUntilIdle()
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(2), any())
        }

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
            verify(callSessionRepository, never()).setCallStartTime(any(), eq(0L))
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

            whenever(telephonyManager.getCallStateForSubscription()).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Both should be closed due to global IDLE safety
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(2), eq(0L))
        }

    @Test
    fun `onCallStarted clears stale session if boot time is invalid`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: saved boot time is in the future relative to current
            timeProvider.setRealtime(5000L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(10000L))

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert: verify clearSession was called (which sets 0) before starting new
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), argThat { this > 0L })
        }

    @Test
    fun `onCallStarted handles clearSession failure for stale session and continues`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: stale session
            timeProvider.setRealtime(5000L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(10000L))

            // Arrange: clearSession (setCallStartTime) throws exception
            whenever(callSessionRepository.setCallStartTime(eq(1), eq(0L)))
                .thenThrow(RuntimeException("Clear failed"))

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert: verify that even with clearSession failure, new startTime was still set
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), argThat { this > 0L })
        }

    @Test
    fun `handleCallStartInternal sets error status on DataStore timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: Flow that never emits triggers timeout in readWithRetry
            whenever(callSessionRepository.getCallStartTime(any())).thenReturn(MutableSharedFlow())

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert
            val status = callTracker.status.first()
            assertTrue(status is TrackingStatus.Error)
            assertTrue((status as TrackingStatus.Error).message?.contains("Timeout reading session data") == true)

            // Verify session WAS cleared for safety
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
        }

    @Test
    fun `handleCallStartInternal sets error status on DataStore IOException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange
            whenever(callSessionRepository.getCallStartTime(any()))
                .thenReturn(
                    kotlinx.coroutines.flow.flow {
                        throw java.io.IOException("Disk full")
                    },
                )

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert
            val status = callTracker.status.first()
            assertTrue(status is TrackingStatus.Error)
            assertEquals("Disk full", (status as TrackingStatus.Error).message)
        }

    @Test
    fun `onCallStarted sets permission missing status on SecurityException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenThrow(SecurityException())

            callTracker.onCallStarted(-1)
            advanceUntilIdle()

            val status = callTracker.status.first()
            assertTrue((status is TrackingStatus.Error) && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun `onCallEnded sets permission missing status on SecurityException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            whenever(telephonyManager.getCallStateForSubscription()).thenThrow(SecurityException())

            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            val status = callTracker.status.first()
            assertTrue((status is TrackingStatus.Error) && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun `checkPermissions clears Error status when permission is restored`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // 1. Устанавливаем статус ошибки из-за отсутствия разрешения
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            callTracker.onCallStarted(1)
            advanceUntilIdle()

            assertTrue(callTracker.status.value is TrackingStatus.Error)

            // 2. Восстанавливаем разрешение
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            // Инициируем вызов, который проверит разрешения
            callTracker.onCallStarted(1)
            advanceUntilIdle()

            // 3. Статус больше не должен быть Error
            val status = callTracker.status.value
            assertTrue("Status should not be Error, but was $status", status !is TrackingStatus.Error)
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
    fun `attribution failure keeps error status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: Force attribution failure in processCallDuration
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            val startTime = now - 1000L
            val bootTime = realtime - 1000L
            val uptime = timeProvider.uptimeMillis() - 1000L
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
            assertEquals("Attribution failed for subId 1", status.message)
        }

    @Test
    fun `recover clears error status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val realtime = timeProvider.elapsedRealtime()
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(realtime - 1000L))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(timeProvider.uptimeMillis() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(-1)
            timeProvider.advance(10000)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()
            assertTrue("Expected Error status before recovery", callTracker.status.value is TrackingStatus.Error)

            // Now "fix" the problem and recover
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            // Act
            callTracker.recover()
            advanceUntilIdle()
            testDispatcher.scheduler.advanceUntilIdle()

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

            timeProvider.advance(10000)

            // Act
            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Assert
            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
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
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
        }

    @Test
    fun `call duration excludes deep sleep`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(now - 1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(currentRealtime))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(currentUptime))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            timeProvider.advance(60000) // 60s duration -> 1 min (uptime)
            // Advance 40s realtime only (deep sleep, not counted in uptime)
            timeProvider.advanceRealtimeOnly(40000)

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Duration should be exactly 60 seconds (1 minute), as deep sleep is excluded from uptime delta
            verify(simUsageRepository, atLeastOnce()).incrementMinutesUsed(eq("id_0"), eq(1))
        }

    @Test
    fun `call tracking sets error on settings timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: flow that never emits triggers firstWithTimeout to return null (on real timeout)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert: verify that error status was set and call start state was NOT saved
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals("Settings DataStore read timeout", (status as TrackingStatus.Error).message)
            verify(callSessionRepository, never()).setCallStartTime(eq(1), any())
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

            // Telephony state OFFHOOK to trigger handleAmbiguousIdle
            @Suppress("DEPRECATION") // Testing legacy API path for API < 31
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
            whenever(telephonyManager.getCallStateForSubscription()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            // Act: End call with unknown subId
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Assert: verify NO sessions were cleared and NO minutes were added
            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
            verify(callSessionRepository, never()).setCallStartTime(any(), eq(0L))
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

            // currentUptime = now + 1000L (via advance)
            timeProvider.advance(1000L)
            // currentRealtime = realtime + 1000L > savedBootTime = realtime - 1000L -> valid

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
        }

    @Test
    fun `handleCallStartInternal timeout keeps Error status after onCallStarted even if active calls exist`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: flow that never emits causes timeout in readWithRetry
            whenever(callSessionRepository.getCallStartTime(any())).thenReturn(MutableSharedFlow())
            // Simulate that there are active calls in the repository
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(2))

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert
            val status = callTracker.status.first()
            assertTrue("Expected Error status, but got $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
            assertTrue(status.message?.contains("Timeout") == true)
        }

    @Test
    fun `loadCallSessionData handle timeout with clearSession failure keeps original cause`() =
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

            // Assert: status should contain the ORIGINAL timeout message
            val status = callTracker.status.value as TrackingStatus.Error
            assertTrue(
                "Expected status to contain timeout info, but was: ${status.message}",
                status.message?.contains("Session read timed out") == true,
            )

            // Verify clearSession (setCallStartTime) WAS called
            verify(callSessionRepository, times(1)).setCallStartTime(eq(1), eq(0L))
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
            @Suppress("DEPRECATION") // Testing legacy API path for API < 31
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
            whenever(telephonyManager.getCallStateForSubscription()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            // Trigger timeout for all reads
            whenever(callSessionRepository.getCallStartTime(any())).thenReturn(MutableSharedFlow())

            // Act
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Assert: No minutes incremented
            verify(simUsageRepository, never()).incrementMinutesUsed(any(), any())
            // No calls cleared
            verify(callSessionRepository, never()).setCallStartTime(any(), eq(0L))
        }

    @Test
    fun `onCallEnded with unknown subId and call tracking disabled does NOT clear other sessions`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: Call tracking disabled
            whenever(
                appSettingsRepository.getAppSettings(),
            ).thenReturn(
                flowOf(
                    AppSettings(callTrackingEnabled = false),
                ),
            )

            // Multiple active calls
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))

            // Act
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Assert: No sessions cleared
            verify(callSessionRepository, never()).setCallStartTime(any(), any())
        }

    @Test
    fun `endCall preserves session on write failure`() =
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
            whenever(simUsageRepository.incrementMinutesUsed(eq(identity), any()))
                .thenThrow(RuntimeException("Write failed"))

            timeProvider.advance(1000)

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            // Assert
            // 1. Session is NOT cleared
            verify(callSessionRepository, never()).setCallStartTime(eq(subId), eq(0L))

            // 2. Status is Error with specific message
            val status = callTracker.status.value as TrackingStatus.Error
            assertEquals("Call duration write failed, session preserved", status.message)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, status.type)
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
            callTracker.onCallEnded(-1)
            advanceUntilIdle()

            // Assert: No crash, and status is PERMISSION_MISSING
            val status = callTracker.status.value as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, status.type)
        }

    @Test
    fun `endCall sets Error on extremely long session and preserves it`() =
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

            // Assert: verify session was NOT cleared
            verify(callSessionRepository, never()).setCallStartTime(eq(subId), eq(0L))
            // Status should be Error (Attribution failed) from handleStaleSession
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

            // Trigger unexpected exception in endCall -> loadCallSessionData -> resolveIdentity
            whenever(permissionManager.getActiveSubscriptions()).thenThrow(RuntimeException("Unexpected crash"))

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
    fun `write failure at MAX_WRITE_ATTEMPTS clears session and sets DATABASE_ERROR status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            val subId = 1
            val identity = "id_1"
            val now = timeProvider.now()
            val currentRealtime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            // Setup active session data that will persist across attempts
            whenever(callSessionRepository.getCallStartTime(subId)).thenReturn(flowOf(now - 60000L))
            whenever(callSessionRepository.getBootTime(subId)).thenReturn(flowOf(currentRealtime - 60000L))
            whenever(callSessionRepository.getUptime(subId)).thenReturn(flowOf(currentUptime - 60000L))
            whenever(callSessionRepository.getCallIdentity(subId)).thenReturn(flowOf(identity))

            // Simulate write failure in simUsageRepository
            whenever(simUsageRepository.incrementMinutesUsed(eq(identity), any()))
                .thenThrow(RuntimeException("Write failed"))

            // Attempt 1
            callTracker.onCallEnded(subId)
            advanceUntilIdle()
            assertEquals(
                "Call duration write failed, session preserved",
                (callTracker.status.value as TrackingStatus.Error).message,
            )

            // Attempt 2
            callTracker.onCallEnded(subId)
            advanceUntilIdle()
            assertEquals(
                "Call duration write failed, session preserved",
                (callTracker.status.value as TrackingStatus.Error).message,
            )

            // Attempt 3 (MAX_WRITE_ATTEMPTS)
            // Строгое соблюдение API: на AOSP DataStore.edit не бросает исключений.
            // Сбой записи возможен только при деградации хранилища на кастомных прошивках.
            // Тест проверяет поведение при MAX_WRITE_ATTEMPTS: сессия принудительно очищается,
            // чтобы не допустить перманентной блокировки трекинга.
            callTracker.onCallEnded(subId)
            advanceUntilIdle()

            val status = callTracker.status.value as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, status.type)
            assertTrue(status.message?.contains("Session cleared, call data lost") == true)

            // Verify session WAS cleared
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(subId), eq(0L))
        }
}
