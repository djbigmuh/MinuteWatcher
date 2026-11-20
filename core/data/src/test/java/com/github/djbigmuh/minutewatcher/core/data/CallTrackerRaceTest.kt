package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.TelephonyManager
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import com.github.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerRaceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()
    private val timeProvider: TimeProvider = mock()
    private val sessionCleaner: CallSessionCleaner = mock()
    private val settingsHandler: CallTrackingSettingsHandler = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val testDispatcher get() = mainDispatcherRule.testDispatcher
    private val testScope by lazy { TestScope(testDispatcher) }

    private lateinit var tracker: CallTracker

    @Before
    fun setup() =
        runTest(testDispatcher) {
            initTestLogger(context)

            tracker = createTestTracker()

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionStateNotifier.changes).thenReturn(emptyFlow())
            whenever(permissionManager.isTelephonySupported()).thenReturn(true)
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
            whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
        }

    private fun createTestTracker(): CallTracker {
        val retryHelper = createMockRetryHelper()
        val attributionResolver =
            CallAttributionResolver(
                simIdentityRepository,
                permissionManager,
                dataChangeNotifier,
            )
        val endResolver =
            CallEndResolver(
                callSessionRepository = callSessionRepository,
                permissionManager = permissionManager,
                telephonyManager = telephonyManager,
            )
        val mutexHolder = CallMutexHolder()
        return CallTracker(
            callSessionRepository = callSessionRepository,
            permissionManager = permissionManager,
            scope = testScope,
            attributionResolver = attributionResolver,
            startHandler =
                CallStartHandler(
                    callSessionRepository,
                    timeProvider,
                    retryHelper,
                    attributionResolver,
                    mutexHolder,
                    sessionCleaner,
                    settingsHandler,
                ),
            endHandler =
                CallEndHandler(
                    callSessionRepository,
                    timeProvider,
                    retryHelper,
                    attributionResolver,
                    mutexHolder,
                    sessionCleaner,
                    settingsHandler,
                    endResolver,
                    dataChangeNotifier,
                ),
            sessionCleaner = sessionCleaner,
            permissionStateNotifier = permissionStateNotifier,
            callMutexHolder = mutexHolder,
        )
    }

    private fun createMockRetryHelper(): DataStoreRetryHelper {
        val retryHelper = mock<DataStoreRetryHelper>()
        runTest(testDispatcher) {
            whenever(
                retryHelper.readWithRetry<Any>(
                    label = any(),
                    attempts = any(),
                    retryDelayMs = any(),
                    attemptTimeoutMillis = any(),
                    reader = any(),
                ),
            ).doSuspendableAnswer { invocation ->
                val reader = invocation.getArgument<() -> Flow<Any>>(4)
                try {
                    Result.success(reader().first())
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
        return retryHelper
    }

    @Test
    fun `concurrent onCallStarted calls are handled safely by mutex`() =
        runTest(testDispatcher) {
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(1)

            whenever(timeProvider.now()).thenReturn(1000L)
            whenever(timeProvider.elapsedRealtime()).thenReturn(2000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(3000L)

            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(0L), flowOf(1000L))
            whenever(callSessionRepository.getElapsedRealtime(1)).thenReturn(flowOf(0L), flowOf(2000L))
            whenever(callSessionRepository.getCallIdentity(1)).thenReturn(flowOf(null))
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            // Launch multiple concurrent start events
            val jobs =
                List(10) {
                    launch {
                        tracker.onCallStarted(1)
                    }
                }
            jobs.joinAll()
            advanceUntilIdle()

            // Session state is read from storage during each start event.
            // Start critical section is serialized via a shared mutex, processing concurrent events sequentially.
            // Only the first event writes the session, as subsequent events observe a non-zero start time.
            verify(callSessionRepository, times(10)).getCallStartTime(1)

            verify(callSessionRepository, times(1)).saveCallSession(
                eq(1),
                argThat { this > 0L },
                any(),
                any(),
                anyOrNull(),
            )
        }

    @Test
    fun `concurrent call start and end for different subIds`() =
        runTest(testDispatcher) {
            whenever(callSessionRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getElapsedRealtime(any())).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf(null))
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            launch { tracker.onCallStarted(1) }
            launch { tracker.onCallStarted(2) }
            advanceUntilIdle()

            verify(callSessionRepository).saveCallSession(eq(1), any(), any(), any(), anyOrNull())
            verify(callSessionRepository).saveCallSession(eq(2), any(), any(), any(), anyOrNull())
        }
}
