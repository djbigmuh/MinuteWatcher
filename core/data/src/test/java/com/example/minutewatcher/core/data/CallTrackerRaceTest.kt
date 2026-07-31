package com.example.minutewatcher.core.data

import android.telephony.TelephonyManager
import com.example.minutewatcher.core.android.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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
    }

    @Test
    fun `call is tracked if enabled in DataStore even if StateFlow not updated`() =
        runTest {
            // Mock getAppSettings().first() to return enabled=true
            whenever(
                appSettingsRepository.getAppSettings(),
            ).thenReturn(
                flowOf(
                    com.example.minutewatcher.core.model
                        .AppSettings(callTrackingEnabled = true),
                ),
            )

            whenever(permissionManager.getDefaultVoiceSubscriptionId()).thenReturn(1)
            whenever(callSessionRepository.getCallStartTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getBootTime(1)).thenReturn(flowOf(0L))
            whenever(callSessionRepository.getActiveCallSubIds()).thenReturn(emptyList())

            tracker.onCallStarted(1)

            verify(callSessionRepository).setCallStartTime(eq(1), any())
        }
}
