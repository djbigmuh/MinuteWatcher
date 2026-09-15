package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallStartHandlerUnitTest {
    private val callSessionRepository: CallSessionRepository = mock()
    private val timeProvider: TimeProvider = mock()
    private val attributionResolver: CallAttributionResolver = mock()
    private val sessionCleaner: CallSessionCleaner = mock()
    private val settingsHandler: CallTrackingSettingsHandler = mock()
    private val context: CallTrackingContext = mock()
    private val retryHelper = DataStoreRetryHelper()
    private val mutexHolder = CallMutexHolder()

    private lateinit var handler: CallStartHandler

    @Before
    fun setup() {
        initTestLogger()
        handler =
            CallStartHandler(
                callSessionRepository,
                timeProvider,
                retryHelper,
                attributionResolver,
                mutexHolder,
                sessionCleaner,
                settingsHandler,
            )
    }

    @Test
    fun `executeCallStart records call start when enabled and permitted`() =
        runTest {
            // Arrange
            whenever(context.checkPermissions()).thenReturn(true)
            whenever(attributionResolver.resolveTargetSubId(1)).thenReturn(1)
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))

            whenever(timeProvider.now()).thenReturn(1000L)
            whenever(timeProvider.elapsedRealtime()).thenReturn(2000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(3000L)

            // Act
            handler.executeCallStart(1, eventElapsedRealtime = -1L, context = context)

            // Assert
            verify(callSessionRepository).setCallStartTime(1, 1000L)
            verify(callSessionRepository).setBootTime(1, 2000L)
            verify(callSessionRepository).setCallIdentity(1, null)

            // Regression: Should not retry null identity, so time should be 0
            Assert.assertEquals("Virtual time should not advance for null identity", 0, testScheduler.currentTime)
        }
}
