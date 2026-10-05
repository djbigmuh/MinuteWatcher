package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerStartTest : CallTrackerTestFixtures() {
    private val settingsHandler: CallTrackingSettingsHandler = mock()

    @Before
    override fun setup() {
        super.setup()
        kotlinx.coroutines.runBlocking {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
        }
    }

    @Test
    fun `test call start saves state for specific subId`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository, atLeastOnce()).saveCallSession(eq(1), any(), any(), any(), anyOrNull())
        }

    @Test
    fun `onCallStarted clears stale session if boot time is invalid`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange: saved boot time is in the future relative to current
            timeProvider.setRealtime(5000L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(10000L))

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert: verify clearSession was called (which sets 0) before starting new
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
            verify(callSessionRepository, atLeastOnce()).saveCallSession(
                eq(1),
                argThat { this > 0L },
                any(),
                any(),
                anyOrNull(),
            )
        }

    @Test
    fun `onCallStarted handles clearSession failure for stale session and continues`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange: stale session
            timeProvider.setRealtime(5000L)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(10000L))

            // Arrange: clearSession throws exception
            whenever(callSessionRepository.clearCallSession(eq(1)))
                .thenThrow(IllegalStateException("Clear failed"))

            // Act
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert: verify that even with clearSession failure, new session was still saved
            verify(callSessionRepository, atLeastOnce()).saveCallSession(
                eq(1),
                argThat { this > 0L },
                any(),
                any(),
                anyOrNull(),
            )
        }

    @Test
    fun `handleCallStartInternal sets error status on DataStore timeout`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange: Flow that never emits triggers timeout in readWithRetry
            whenever(callSessionRepository.getCallStartTime(any())).thenReturn(MutableSharedFlow())

            // Act
            callTracker.onCallStarted(subId = 1)
            // Wait for internal readWithRetry attempts: 3 attempts, 500ms delay, 1000ms delay
            testScheduler.advanceTimeBy(3000.milliseconds)
            advanceUntilIdle()

            // Assert
            val status = callTracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            val error = status as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, error.type)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, error.code)

            // Verify session WAS cleared for safety
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
        }

    @Test
    fun `handleCallStartInternal sets error status on DataStore IOException`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Arrange
            whenever(callSessionRepository.getCallStartTime(any()))
                .thenReturn(
                    flow {
                        throw IOException("Disk full")
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
            callTracker.ensureStarted()
            advanceUntilIdle()
            // Mock both direct call AND permission check to throw
            whenever(permissionManager.hasPhoneStatePermission()).thenThrow(SecurityException())
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenThrow(SecurityException())

            callTracker.onCallStarted(-1)
            advanceUntilIdle()

            val status = callTracker.status.first()
            assertTrue((status is TrackingStatus.Error) && (status.type == TrackingStatus.ErrorType.PERMISSION_MISSING))
        }

    @Test
    fun `checkPermissions clears Error status when permission is restored`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
            // 1. Set error status due to missing permission
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            callTracker.onCallStarted(1)
            advanceUntilIdle()

            assertTrue(callTracker.status.value is TrackingStatus.Error)

            // 2. Restore permission
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            // Initiate call to trigger permission check
            callTracker.onCallStarted(1)
            advanceUntilIdle()

            // 3. Status should no longer be Error
            val status = callTracker.status.value
            assertTrue("Status should not be Error, but was $status", status !is TrackingStatus.Error)
        }

    @Test
    fun `successful onCallStarted triggers status refresh in finalize block`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            clearInvocations(callSessionRepository)

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository).saveCallSession(eq(1), any(), any(), any(), anyOrNull())
            verify(callSessionRepository, atLeastOnce()).getActiveCallSubIds()
        }

    @Test
    fun `failed onCallStarted skips status refresh in finalize block`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            clearInvocations(callSessionRepository)

            whenever(callSessionRepository.getCallStartTime(1))
                .thenReturn(flow { throw IOException("Read error") })

            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            verify(callSessionRepository, never()).getActiveCallSubIds()
        }

    @Test
    fun `onCallStarted handles race where tracking is disabled during execution`() =
        runTest(testDispatcher) {
            setupCommonMocks()

            val settingsFlow = MutableStateFlow(AppSettings(callTrackingEnabled = true))
            whenever(appSettingsRepository.getAppSettings()).thenReturn(settingsFlow)

            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // Disable tracking before the check inside the mutex occurs
            settingsFlow.value = AppSettings(callTrackingEnabled = false)

            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Verify session was NOT started (only cleanup called)
            verify(callSessionRepository, never()).saveCallSession(
                eq(1),
                argThat { this > 0L },
                any(),
                any(),
                anyOrNull(),
            )
            verify(callSessionRepository, atLeastOnce()).clearCallSession(eq(1))
        }

    @Test
    fun `handleCallStartInternal timeout keeps Error status after onCallStarted even if active calls exist`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()
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
            val error = status as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, error.type)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, error.code)
        }

    @Test
    fun `onCallStarted does not overwrite startTime if session is already active`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // 1. Start first call
            timeProvider.setTime(1000L)
            timeProvider.setRealtime(1000L)
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Verify first start time is set
            verify(callSessionRepository).saveCallSession(eq(1), eq(1000L), any(), any(), anyOrNull())

            // 2. Mock that there's an active session
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            // 3. Second OFFHOOK event
            timeProvider.setTime(2000L)
            timeProvider.setRealtime(2000L)
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Verify that 2000L was NOT set as startTime
            verify(callSessionRepository, never()).saveCallSession(eq(1), eq(2000L), any(), any(), anyOrNull())
        }

    @Test
    fun `onCallStarted ignores event if SIM identity changed during active call`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            // Arrange: active session with identity "id_1"
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))

            // Act: NEW event with identity "id_new"
            // We need to update observeActiveSims because resolveIdentity uses it
            val mockIdentity =
                SimIdentity(
                    stableId = "id_new",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.ICCID,
                    isStable = true,
                )
            val mockSim =
                ActiveSimInfo(
                    identity = mockIdentity,
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = "id_new",
                    displayName = "SIM 1",
                    carrierName = "Carrier",
                    mccMnc = "12345",
                    isDefaultData = true,
                    isDefaultVoice = true,
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(mockSim)))

            timeProvider.setTime(2000L)
            timeProvider.setRealtime(2000L) // Ensure session is not stale
            callTracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            // Assert: verify no new session was saved
            verify(callSessionRepository, never()).saveCallSession(eq(1), eq(2000L), any(), any(), anyOrNull())
        }

    @Test
    fun `onCallStarted rethrows IOException when saveCallSession fails and sets DATABASE_ERROR status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            whenever(callSessionRepository.saveCallSession(any(), any(), any(), any(), anyOrNull()))
                .thenAnswer { throw IOException("Save session failed") }

            try {
                callTracker.onCallStarted(subId = 1)
                Assert.fail("Expected IOException to be thrown")
            } catch (e: IOException) {
                assertEquals("Save session failed", e.message)
            }

            val status = callTracker.status.first()
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
            verify(simUsageRepository, never()).incrementMinutesUsedForCall(any(), any(), any())
        }

    @Test
    fun `onCallStarted rethrows SecurityException when saveCallSession fails and sets PERMISSION_MISSING status`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            val callTracker = createTracker()
            callTracker.ensureStarted()
            advanceUntilIdle()

            whenever(callSessionRepository.saveCallSession(any(), any(), any(), any(), anyOrNull()))
                .thenAnswer { throw SecurityException("Permission denied") }

            try {
                callTracker.onCallStarted(subId = 1)
                Assert.fail("Expected SecurityException to be thrown")
            } catch (e: SecurityException) {
                assertEquals("Permission denied", e.message)
            }

            val status = callTracker.status.first()
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (status as TrackingStatus.Error).type)
        }
}
