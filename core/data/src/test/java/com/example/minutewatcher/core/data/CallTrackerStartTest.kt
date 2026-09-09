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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerStartTest : CallTrackerTestFixtures() {
    private val sessionCleaner: CallSessionCleaner = mock()
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

            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), any())
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
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), argThat { this > 0L })
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
            assertTrue(
                "Expected timeout message, but was: ${(status as TrackingStatus.Error).message}",
                status.message?.contains("Timeout") == true,
            )

            // Verify session WAS cleared for safety
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
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
    fun `onCallStarted handles exception and still runs finally status refresh`() =
        runTest(testDispatcher) {
            setupCommonMocks()
            var refreshStatusCalled = false
            val attributionResolver = createAttributionResolver()
            val mutexHolder = createMutexHolder()
            val tracker =
                object : CallTracker(
                    callSessionRepository = callSessionRepository,
                    permissionManager = permissionManager,
                    scope = testScope,
                    attributionResolver = attributionResolver,
                    startHandler = createStartHandler(attributionResolver, mutexHolder),
                    endHandler = createEndHandler(attributionResolver, mutexHolder),
                    sessionCleaner = sessionCleaner,
                    permissionStateNotifier = permissionStateNotifier,
                    callMutexHolder = mutexHolder,
                ) {
                    override suspend fun refreshStatus(force: Boolean) {
                        refreshStatusCalled = true
                        super.refreshStatus(force)
                    }
                }

            whenever(callSessionRepository.getCallStartTime(1))
                .thenReturn(flow { throw IllegalStateException("Simulated crash") })

            tracker.onCallStarted(subId = 1)
            advanceUntilIdle()

            assertTrue("refreshStatus should be called in finally block", refreshStatusCalled)
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

            // Verify session was NOT started (only cleanup set to 0)
            verify(callSessionRepository, never()).setCallStartTime(eq(1), argThat { this > 0L })
            verify(callSessionRepository, atLeastOnce()).setCallStartTime(eq(1), eq(0L))
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
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
            assertTrue(status.message?.contains("Timeout") == true)
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
            verify(callSessionRepository).setCallStartTime(eq(1), eq(1000L))

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
            verify(callSessionRepository, never()).setCallStartTime(eq(1), eq(2000L))
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

            // Assert: verify no new startTime was set
            verify(callSessionRepository, never()).setCallStartTime(eq(1), eq(2000L))
        }
}
