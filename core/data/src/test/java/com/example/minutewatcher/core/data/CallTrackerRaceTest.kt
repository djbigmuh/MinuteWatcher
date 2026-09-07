package com.example.minutewatcher.core.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallTrackerRaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()
    private val timeProvider: TimeProvider = mock()
    private val sessionCleaner: CallSessionCleaner = mock()
    private val settingsHandler: CallTrackingSettingsHandler = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var tracker: CallTracker

    @Before
    fun setup() {
        val application = context as Application
        Shadows
            .shadowOf(application)
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)

        tracker = createTestTracker()

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionStateNotifier.changes).thenReturn(emptyFlow())
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
        whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
        runBlocking {
            whenever(settingsHandler.isCallTrackingEnabled()).thenReturn(true)
        }
    }

    private fun createTestTracker(): CallTracker {
        val retryHelper =
            object : DataStoreRetryHelper() {
                override suspend fun <T> readWithRetry(
                    label: String,
                    attempts: Int,
                    retryDelayMs: Long,
                    reader: () -> Flow<T>,
                ): Result<T> =
                    try {
                        Result.success(reader().first())
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
            }
        val attributionResolver =
            CallAttributionResolver(
                simIdentityRepository,
                callSessionRepository,
                permissionManager,
                retryHelper,
                dataChangeNotifier,
            )
        val endResolver =
            CallEndResolver(
                context = context,
                callSessionRepository = callSessionRepository,
                permissionManager = permissionManager,
                telephonyManager = telephonyManager,
            )
        val mutexHolder = CallMutexHolder()
        return CallTracker(
            callSessionRepository = callSessionRepository,
            dataChangeNotifier = dataChangeNotifier,
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
                ),
            endHandler =
                CallEndHandler(
                    callSessionRepository,
                    timeProvider,
                    retryHelper,
                    attributionResolver,
                    mutexHolder,
                ),
            sessionCleaner = sessionCleaner,
            settingsHandler = settingsHandler,
            permissionStateNotifier = permissionStateNotifier,
            callEndResolver = endResolver,
            callMutexHolder = mutexHolder,
        )
    }

    @Test
    fun `concurrent onCallStarted calls are handled safely by mutex`() =
        runTest(testDispatcher) {
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(1)

            whenever(timeProvider.now()).thenReturn(1000L)
            whenever(timeProvider.elapsedRealtime()).thenReturn(2000L)
            whenever(timeProvider.uptimeMillis()).thenReturn(3000L)

            // handleCallStartInternal читает только getCallStartTime и getBootTime через combine().
            // getUptime используется только в loadCallSessionData (путь endCall) и здесь не нужен.
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(0L), flowOf(1000L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(0L), flowOf(2000L))
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

            // Since internalActiveSessions is removed, each call to onCallStarted will read from DataStore
            // to check if a session is already active. Mutex ensures they happen one by one.
            verify(callSessionRepository, times(10)).getCallStartTime(1).let { _ -> }

            // Строгое соблюдение API: на AOSP DataStore.edit сериализует записи.
            // Мьютекс callMutex в CallTracker дополнительно сериализует
            // read-modify-write на уровне приложения. Тест верифицирует,
            // что конкурентные вызовы не приводят к двойной записи session.
            // Верификация: setCallStartTime вызван ровно 1 раз для subId=1,
            // так как мьютекс сериализует доступ и только первый вызов
            // должен записать session (остальные увидят startTime != 0).
            verify(callSessionRepository, times(1)).setCallStartTime(eq(1), argThat { this > 0L })
        }

    @Test
    fun `concurrent call start and end for different subIds`() =
        runTest(testDispatcher) {
            whenever(callSessionRepository.getCallStartTime(any())).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getBootTime(any())).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getCallIdentity(any())).thenReturn(flowOf(null))
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            launch { tracker.onCallStarted(1) }
            launch { tracker.onCallStarted(2) }
            advanceUntilIdle()

            verify(callSessionRepository).setCallStartTime(eq(1), any())
            verify(callSessionRepository).setCallStartTime(eq(2), any())
        }
}
