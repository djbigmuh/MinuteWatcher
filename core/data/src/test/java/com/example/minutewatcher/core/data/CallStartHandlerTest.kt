package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallStartHandlerTest {
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
    fun `executeCallStart records call start when tracking enabled`() =
        runTest {
            whenever(context.checkPermissions()).thenReturn(true)
            whenever(attributionResolver.resolveTargetSubId(1)).thenReturn(1)
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf("id_1"))
            whenever(attributionResolver.resolveIdentity(1)).thenReturn("id_1")
            whenever(timeProvider.now()).thenReturn(1000L)
            whenever(timeProvider.elapsedRealtime()).thenReturn(5000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(3000L)

            handler.executeCallStart(1, -1L, context)

            verify(callSessionRepository).saveCallSession(
                subId = eq(1),
                startTime = eq(1000L),
                bootTime = eq(5000L),
                uptime = eq(3000L),
                identity = eq("id_1"),
            )
        }

    @Test
    fun `executeCallStart cleans up session when call tracking disabled`() =
        runTest {
            whenever(context.checkPermissions()).thenReturn(true)
            whenever(attributionResolver.resolveTargetSubId(1)).thenReturn(1)
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(false)

            handler.executeCallStart(1, -1L, context)

            verify(sessionCleaner).cleanupCounters(eq(1), eq(context))
        }

    @Test
    fun `executeCallStart refreshes status when targetSubId is unresolvable`() =
        runTest {
            whenever(context.checkPermissions()).thenReturn(true)
            whenever(attributionResolver.resolveTargetSubId(1)).thenReturn(-1)

            handler.executeCallStart(1, -1L, context)

            verify(context).refreshStatus(false)
        }
}
