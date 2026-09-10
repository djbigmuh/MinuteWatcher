package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallEndHandlerUnitTest {
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
            verify(settingsHandler).writeCallDuration(eq("id_1"), eq(1), eq(false), eq(true))
            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
        }
}
