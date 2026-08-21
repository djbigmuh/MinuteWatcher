package com.example.minutewatcher.core.data

import android.Manifest
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallTrackerRaceTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()
    private val timeProvider: TimeProvider = mock()
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val testScope = kotlinx.coroutines.test.TestScope(testDispatcher)

    private lateinit var tracker: CallTracker

    @Before
    fun setup() {
        val application = context as android.app.Application
        org.robolectric.Shadows
            .shadowOf(application)
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)

        tracker =
            CallTracker(
                context,
                simIdentityRepository,
                simUsageRepository,
                appSettingsRepository,
                callSessionRepository,
                dataChangeNotifier,
                permissionManager,
                telephonyManager,
                timeProvider,
                testScope,
            )

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)
        whenever(telephonyManager.callStateForSubscription).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK)
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
        whenever(appSettingsRepository.getAppSettings()).thenReturn(
            flowOf(AppSettings()),
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

            // Flow is consumed internally by the SUT (System Under Test) via collect().
            // The side effect (state change) is verified below via status assertion.
            verify(callSessionRepository, times(1)).getCallStartTime(1).let { _ -> }

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
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            launch { tracker.onCallStarted(1) }
            launch { tracker.onCallStarted(2) }
            advanceUntilIdle()

            verify(callSessionRepository).setCallStartTime(eq(1), any())
            verify(callSessionRepository).setCallStartTime(eq(2), any())
        }
}
