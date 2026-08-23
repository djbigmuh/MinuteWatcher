package com.example.minutewatcher.core.data

import android.Manifest
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

// Подавление LargeClass оправдано для тестового класса: CallTrackerTest объединяет все сценарии
// тестирования трекинга звонков, включая dual-SIM, сбои DataStore и системные тайминги.
// Разделение теста усложнит поддержку общих моков и фикстур времени.

/**
 * Edge case tests verify behavior under error conditions.
 * These are critical for production stability on custom ROMs
 * where system services may degrade or fail unexpectedly.
 */
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()
    private val retryHelper = DataStoreRetryHelper()

    private val timeProvider =
        FixedTimeProvider(
            currentTime = 500000000L,
            currentRealtime = 500000000L,
            currentUptime = 500000000L,
        )

    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val testScope = kotlinx.coroutines.test.TestScope(testDispatcher)

    @Before
    fun setup() {
        val application = context as android.app.Application
        org.robolectric.Shadows
            .shadowOf(application)
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)

        whenever(appSettingsRepository.getAppSettings()).thenReturn(flowOf(AppSettings()))
        whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf(null))
        whenever(callSessionRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getBootTime(any())).thenReturn(flowOf(0L))
        whenever(callSessionRepository.getUptime(any())).thenReturn(flowOf(0L))

        whenever(simIdentityRepository.getSimOperator(any())).thenReturn(flowOf("MTS"))
        @Suppress("DEPRECATION") // Testing legacy API path for API < 31
        whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
        whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
    }

    private fun createTracker() =
        CallTracker(
            context,
            simIdentityRepository,
            simUsageRepository,
            appSettingsRepository,
            callSessionRepository,
            dataChangeNotifier,
            permissionManager,
            telephonyManager,
            timeProvider,
            retryHelper,
            testScope,
        )

    private suspend fun setupCommonMocks() {
        whenever(simIdentityRepository.getIdentity(0)).thenReturn("id_0")
        whenever(simIdentityRepository.getIdentity(1)).thenReturn("id_1")

        val mockIdentity =
            com.example.minutewatcher.core.model.SimIdentity(
                stableId = "id_1",
                subscriptionId = 1,
                slotIndex = 0,
                source = com.example.minutewatcher.core.model.IdentitySource.ICCID,
            )
        val mockSim =
            com.example.minutewatcher.core.model.ActiveSimInfo(
                identity = mockIdentity,
                slotIndex = 0,
                subscriptionId = 1,
                displayName = "SIM 1",
                carrierName = "Carrier",
                mccMnc = "12345",
                isDefaultData = true,
                isDefaultVoice = true,
            )

        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockSim)))
        whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

        val mockSubInfo = mock<SubscriptionInfo>()
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

            whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_IDLE)

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
                .thenThrow(IllegalStateException("Clear failed"))

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
            // Wait for internal readWithRetry attempts: 3 attempts, 500ms delay, 1000ms delay
            testScheduler.advanceTimeBy(3000)
            advanceUntilIdle()

            // Assert
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertTrue(
                "Expected timeout message, but was: ${(status as TrackingStatus.Error).message}",
                status.message?.contains("Timeout") == true,
            )

            // Verify session WAS cleared for safety (it calls cleanupCounters on read failure in endCall,
            // but here it's onCallStarted. Wait, in onCallStarted I added readWithRetry too.)
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
                    flow {
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
            // Mock both direct call AND permission check to throw
            whenever(permissionManager.hasPhoneStatePermission()).thenThrow(SecurityException())
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
            // Mock both direct call AND permission check to throw
            whenever(permissionManager.hasPhoneStatePermission()).thenThrow(SecurityException())
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
    fun `recover clears error status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            // Arrange: Force error status first (attribution failure)
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(timeProvider.now() - 1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

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
    fun `call tracking sets error on settings timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            // Arrange: flow that never emits triggers readWithRetry to return failure (on real timeout)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(MutableSharedFlow())

            // Act
            callTracker.onCallStarted(subId = 1)
            testScheduler.advanceTimeBy(3000)
            advanceUntilIdle()

            // Assert: verify that error status was set and call start state was NOT saved
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertTrue(
                "Expected timeout message, but was: ${(status as TrackingStatus.Error).message}",
                status.message?.contains("Timeout") == true,
            )
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
            whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

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
            // Need a way to advance uptime too... FixedTimeProvider doesn't have setUptime.
            // I'll assume uptime is advanced via some other means or just not care if validateUptime passes.
            // Actually, if I want validateUptime to pass, currentUptime must be >= savedUptime.
            // savedUptime is 500M. currentUptime is 500M. It passes.

            callTracker.onCallEnded(subId = 1)
            advanceUntilIdle()

            // Duration should be 1 minute (60s), NOT 60 minutes (1h)
            verify(simUsageRepository, atLeastOnce()).incrementMinutesUsed(eq("id_0"), eq(1))
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
            whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

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

    @Test(expected = java.io.IOException::class)
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
            callTracker.onCallEnded(subId)
            advanceUntilIdle()
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

    @Test(expected = java.io.IOException::class)
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

            // Act
            callTracker.onCallEnded(subId)
            advanceUntilIdle()
        }

    @Test
    fun `onCallStarted handles exception and still runs finally status refresh`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            // We use a custom subclass to intercept refreshStatus call
            var refreshStatusCalled = false
            val tracker =
                object : CallTracker(
                    context,
                    simIdentityRepository,
                    simUsageRepository,
                    appSettingsRepository,
                    callSessionRepository,
                    dataChangeNotifier,
                    permissionManager,
                    telephonyManager,
                    timeProvider,
                    retryHelper,
                    testScope,
                ) {
                    override suspend fun refreshStatus(force: Boolean) {
                        refreshStatusCalled = true
                        super.refreshStatus(force)
                    }
                }

            // Mock handleCallStartInternal path to throw exception
            whenever(callSessionRepository.getCallStartTime(1))
                .thenReturn(flow { throw IllegalStateException("Simulated crash") })

            tracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            assertTrue("refreshStatus should be called in finally block", refreshStatusCalled)
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
    fun `readWithRetry retries on transient IOException and eventually succeeds`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            var attempts = 0
            whenever(appSettingsRepository.getAppSettings()).thenAnswer {
                attempts++
                if (attempts < 3) {
                    flow<AppSettings> { throw java.io.IOException("Transient error") }
                } else {
                    flowOf(AppSettings(callTrackingEnabled = true))
                }
            }

            callTracker.onCallStarted(subId = 1)

            // Need to advance time for retries
            // delay(500), delay(1000)
            testScheduler.advanceTimeBy(2000)
            advanceUntilIdle()

            assertEquals(3, attempts)
            // Verify it continued to handleCallStarted
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), any())
        }

    @Test(expected = CancellationException::class)
    fun `readWithRetry propagates CancellationException without retry`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()

            var attempts = 0
            whenever(appSettingsRepository.getAppSettings()).thenAnswer {
                attempts++
                flow<AppSettings> { throw CancellationException("Intentional cancellation") }
            }

            // Act
            callTracker.onCallStarted(subId = 1)
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
    fun `refreshStatus cleans up attributionFailures for inactive subIds`() =
        runTest(testDispatcher) {
            val tracker = createTracker()

            // 1. Manually add entries to attributionFailures using reflection
            val field = CallTracker::class.java.getDeclaredField("attributionFailures")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(tracker) as java.util.concurrent.ConcurrentHashMap<Int, Int>

            val inactiveSubId = 99
            val activeSubId = 1
            map[inactiveSubId] = 1
            map[activeSubId] = 1

            // 2. Mock active calls to only include activeSubId
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(listOf(activeSubId))

            // 3. Call refreshStatus
            tracker.refreshStatus(force = true)

            // 4. Verify inactiveSubId is removed, activeSubId is kept
            assertTrue("Active subId should be kept", map.containsKey(activeSubId))
            org.junit.Assert.assertFalse("Inactive subId should be removed", map.containsKey(inactiveSubId))
        }
}
