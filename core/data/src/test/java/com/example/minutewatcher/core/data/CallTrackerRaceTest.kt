package com.example.minutewatcher.core.data

import android.telephony.TelephonyManager
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallTrackerRaceTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val permissionManager: PermissionManager = mock()
    private val telephonyManager: TelephonyManager = mock()
    private val timeProvider: TimeProvider = mock()
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tracker: CallTracker

    @Before
    fun setup() {
        tracker =
            CallTracker(
                simIdentityRepository,
                simUsageRepository,
                appSettingsRepository,
                callSessionRepository,
                dataChangeNotifier,
                permissionManager,
                telephonyManager,
                kotlinx.coroutines.CoroutineScope(testDispatcher),
                timeProvider,
            )

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(appSettingsRepository.getAppSettings()).thenReturn(
            flowOf(AppSettings(callTrackingEnabled = true)),
        )
    }

    @Test
    fun `concurrent onCallStarted calls are handled safely by mutex`() =
        runTest {
            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(1)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(0L))
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

            // Verification of interaction count
            verify(callSessionRepository, times(10)).getCallStartTime(1)
        }

    @Test
    fun `concurrent call start and end for different subIds`() =
        runTest {
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
