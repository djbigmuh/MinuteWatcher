package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallEndTrackingSettingsTest {
    private val callSessionRepository: CallSessionRepository = mock()
    private val timeProvider: TimeProvider = mock()
    private val attributionResolver: CallAttributionResolver = mock()
    private val callMutexHolder = CallMutexHolder()
    private val sessionCleaner: CallSessionCleaner = mock()
    private val settingsHandler: CallTrackingSettingsHandler = mock()
    private val callEndResolver: CallEndResolver = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()

    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `call end handler does not read session data when tracking is disabled`() =
        runTest {
            val handler =
                CallEndHandler(
                    callSessionRepository,
                    timeProvider,
                    mock(), // retryHelper
                    attributionResolver,
                    callMutexHolder,
                    sessionCleaner,
                    settingsHandler,
                    callEndResolver,
                    dataChangeNotifier,
                )

            val context: CallTrackingContext = mock()
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(false)

            val subId = 1
            handler.endCall(subId, 1000L, true, context)

            // 1. Verify cleanup was called
            verify(sessionCleaner).cleanupCounters(eq(subId), any())

            // 2. Verify NO DataStore reads were performed
            verify(callSessionRepository, never()).getCallStartTime(any())
            verify(callSessionRepository, never()).getElapsedRealtime(any())
            verify(callSessionRepository, never()).getUptime(any())
            verify(callSessionRepository, never()).getCallIdentity(any())
        }
}
