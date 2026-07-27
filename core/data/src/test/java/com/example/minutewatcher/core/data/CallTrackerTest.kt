package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.TelephonyManager
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerTest {
    private val context: Context = mock()
    private val simRepository: SimRepository = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val updateCoordinator: UpdateCoordinator = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var callTracker: CallTracker

    @Before
    fun setup() =
        runTest {
            whenever(settingsRepository.getCallTrackingEnabled()).thenReturn(flowOf(true))
            whenever(settingsRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
            whenever(settingsRepository.getBootTime(any())).thenReturn(flowOf(0L))
            whenever(settingsRepository.getCallIdentity(any())).thenReturn(flowOf(null))

            whenever(simRepository.getIdentity(0)).thenReturn("id_0")
            whenever(simRepository.getIdentity(1)).thenReturn("id_1")
            whenever(simRepository.getSimOperator(any())).thenReturn(flowOf("MTS"))
            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)

            callTracker =
                CallTracker(
                    context,
                    simRepository,
                    settingsRepository,
                    updateCoordinator,
                    permissionManager,
                    telephonyManager,
                    testScope,
                )
        }

    @Test
    fun `test call start saves state for specific subId`() =
        runTest {
            callTracker.onCallStarted(subId = 1)

            verify(settingsRepository).setCallStartTime(eq(1), any())
            verify(settingsRepository).setCallIdentity(eq(1), anyOrNull())
        }

    @Test
    fun `test call end with same subId clears state`() =
        runTest {
            whenever(settingsRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(settingsRepository.getBootTime(1)).thenReturn(flowOf(100L))

            callTracker.onCallEnded(subId = 1)

            verify(settingsRepository).setCallStartTime(1, 0)
            verify(settingsRepository).setBootTime(1, 0)
        }

    @Test
    fun `test dual SIM concurrent call tracking`() =
        runTest {
            // Start call on subId 1
            callTracker.onCallStarted(subId = 1)
            verify(settingsRepository).setCallStartTime(eq(1), any())

            // Start call on subId 2
            callTracker.onCallStarted(subId = 2)
            verify(settingsRepository).setCallStartTime(eq(2), any())
        }

    @Test
    fun `onBoot clears all call sessions`() =
        runTest {
            callTracker.onBoot()

            verify(settingsRepository).clearAllCallSessions()
        }

    @Test
    fun `onCallEnded with unknown subId and single active call attributes correctly`() =
        runTest {
            whenever(settingsRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            whenever(settingsRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(settingsRepository.getBootTime(1)).thenReturn(flowOf(100L))

            callTracker.onCallEnded(-1)

            verify(settingsRepository).setCallStartTime(1, 0)
        }

    @Test
    fun `onCallEnded with unknown subId and multiple calls uses default voice attribution`() =
        runTest {
            whenever(settingsRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(settingsRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(settingsRepository.getBootTime(1)).thenReturn(flowOf(100L))
            whenever(settingsRepository.getCallStartTime(2)).thenReturn(flowOf(2000L))
            whenever(settingsRepository.getBootTime(2)).thenReturn(flowOf(200L))

            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(2)

            callTracker.onCallEnded(-1)

            // Should attribute to subId 2
            verify(settingsRepository).setCallStartTime(2, 0)
            verify(settingsRepository, never()).setCallStartTime(1, 0)
        }

    @Test
    fun `onCallEnded with unknown subId and global IDLE cleans up all`() =
        runTest {
            whenever(settingsRepository.getActiveCallSubIds()).thenReturn(listOf(1, 2))
            whenever(settingsRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(settingsRepository.getBootTime(1)).thenReturn(flowOf(100L))
            whenever(settingsRepository.getCallStartTime(2)).thenReturn(flowOf(2000L))
            whenever(settingsRepository.getBootTime(2)).thenReturn(flowOf(200L))

            whenever(telephonyManager.callState).thenReturn(TelephonyManager.CALL_STATE_IDLE)

            callTracker.onCallEnded(-1)

            // Both should be closed due to global IDLE safety
            verify(settingsRepository).setCallStartTime(1, 0)
            verify(settingsRepository).setCallStartTime(2, 0)
        }

    @Test
    fun `onCallStarted clears stale session if boot time is invalid`() =
        runTest {
            // Arrange: saved boot time is in the future relative to current (0 in test)
            whenever(settingsRepository.getCallStartTime(1)).thenReturn(flowOf(5000L))
            whenever(settingsRepository.getBootTime(1)).thenReturn(flowOf(10000L))

            // Act
            callTracker.onCallStarted(subId = 1)

            // Assert: verify clearSession was called (which sets 0) before starting new
            verify(settingsRepository, atLeastOnce()).setCallStartTime(1, 0)
            verify(settingsRepository).setCallStartTime(eq(1), argThat { this > 0 })
        }

    @Test
    fun `onCallStarted sets permission missing status on SecurityException`() =
        runTest {
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenThrow(SecurityException())

            callTracker.onCallStarted(-1)

            val status = callTracker.status.first()
            assert(status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun `onCallEnded sets permission missing status on SecurityException`() =
        runTest {
            whenever(settingsRepository.getActiveCallSubIds()).thenReturn(listOf(1))
            whenever(telephonyManager.callState).thenThrow(SecurityException())

            callTracker.onCallEnded(-1)

            val status = callTracker.status.first()
            assert(status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun `processCallDuration sets active status on success`() =
        runTest {
            val startTime = 1000L
            val bootTime = 500L
            whenever(settingsRepository.getCallStartTime(1)).thenReturn(flowOf(startTime))
            whenever(settingsRepository.getBootTime(1)).thenReturn(flowOf(bootTime))
            whenever(settingsRepository.getCallIdentity(1)).thenReturn(flowOf("id_0"))

            // Advance clock so currentBootTime - savedBootTime > 0
            org.robolectric.shadows.ShadowSystemClock
                .advanceBy(java.time.Duration.ofSeconds(10))

            callTracker.onCallEnded(subId = 1)

            val status = callTracker.status.first()
            assertEquals(TrackingStatus.Active, status)
        }
}
