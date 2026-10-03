package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallEndHandlerTest {
    private val callSessionRepository: CallSessionRepository = mock()
    private val timeProvider: TimeProvider = mock()
    private val attributionResolver: CallAttributionResolver = mock()
    private val sessionCleaner: CallSessionCleaner = mock()
    private val settingsHandler: CallTrackingSettingsHandler = mock()
    private val callEndResolver: CallEndResolver = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val context: CallTrackingContext = mock()
    private val retryHelper = DataStoreRetryHelper()
    private val mutexHolder = CallMutexHolder()

    private lateinit var handler: CallEndHandler

    @Before
    fun setup() {
        handler =
            CallEndHandler(
                callSessionRepository,
                timeProvider,
                retryHelper,
                attributionResolver,
                mutexHolder,
                sessionCleaner,
                settingsHandler,
                callEndResolver,
                dataChangeNotifier,
            )
    }

    @Test
    fun `endCall records duration when valid session exists`() =
        runTest {
            // Arrange
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(3000L))

            whenever(timeProvider.elapsedRealtime()).thenReturn(65000L) // 60 seconds duration
            whenever(timeProvider.uptimeMillis()).thenReturn(63000L)

            whenever(attributionResolver.resolveTargetIdentity(eq(1), eq("id_1"))).thenReturn("id_1" to false)

            // Act
            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            // Assert
            verify(settingsHandler).writeCallDuration(eq("id_1"), eq(1), eq(false), eq(true), eq("1000|5000"))
            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
        }

    @Test
    fun `two sessions with same elapsedRealtime but different startTimeMillis produce different signatures`() =
        runTest {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(3000L))
            whenever(timeProvider.elapsedRealtime()).thenReturn(65000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(63000L)
            whenever(attributionResolver.resolveTargetIdentity(eq(1), eq("id_1"))).thenReturn("id_1" to false)

            // Session 1: startTime = 1000L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            // Session 2: startTime = 2000L
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(2000L))
            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            verify(settingsHandler).writeCallDuration(eq("id_1"), eq(1), eq(false), eq(true), eq("1000|5000"))
            verify(settingsHandler).writeCallDuration(eq("id_1"), eq(1), eq(false), eq(true), eq("2000|5000"))
        }

    @Test
    fun `duplicate call end event delivery for same session passes same signature to settings handler`() =
        runTest {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(3000L))
            whenever(timeProvider.elapsedRealtime()).thenReturn(65000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(63000L)
            whenever(attributionResolver.resolveTargetIdentity(eq(1), eq("id_1"))).thenReturn("id_1" to false)

            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)
            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            verify(settingsHandler, times(2))
                .writeCallDuration(eq("id_1"), eq(1), eq(false), eq(true), eq("1000|5000"))
        }

    @Test
    fun `endCall clears session without recording duration when savedBootTime is greater than current`() =
        runTest {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(100000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(3000L))

            whenever(timeProvider.elapsedRealtime()).thenReturn(50000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(40000L)

            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
            verify(settingsHandler, never()).writeCallDuration(any(), any(), any(), any(), any())
        }

    @Test
    fun `endCall clears session without recording duration when call duration exceeds max limit`() =
        runTest {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(1000L))

            whenever(timeProvider.elapsedRealtime()).thenReturn(1000L + Constants.Calls.MAX_CALL_DURATION_MS + 10000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(50000L)

            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
            verify(settingsHandler, never()).writeCallDuration(any(), any(), any(), any(), any())
        }

    @Test
    fun `endCall clears session without recording duration when call duration is zero`() =
        runTest {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(3000L))

            whenever(timeProvider.elapsedRealtime()).thenReturn(5000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(3000L)
            whenever(attributionResolver.resolveTargetIdentity(eq(1), eq("id_1"))).thenReturn("id_1" to false)

            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
            verify(settingsHandler, never()).writeCallDuration(any(), any(), any(), any(), any())
        }

    @Test
    fun `endCall clears session without recording duration when uptime is negative`() =
        runTest {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(5000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(10000L))

            whenever(timeProvider.elapsedRealtime()).thenReturn(15000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(2000L)

            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
            verify(settingsHandler, never()).writeCallDuration(any(), any(), any(), any(), any())
        }

    @Test
    fun `endCall clears session when bootTime is zero`() =
        runTest {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(callSessionRepository.getUptime(1)).thenReturn(flowOf(0L))

            whenever(timeProvider.elapsedRealtime()).thenReturn(15000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(20000L)

            handler.endCall(1, eventElapsedRealtime = -1L, notify = true, context = context)

            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
            verify(settingsHandler, never()).writeCallDuration(any(), any(), any(), any(), any())
        }
}
