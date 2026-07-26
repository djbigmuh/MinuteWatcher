package com.example.minutewatcher.core.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerTest {
    private val context: Context = mock()
    private val simRepository: SimRepository = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val networkStatsManager: NetworkStatsManager = mock()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val lastDataSubIdFlow = MutableStateFlow(-1)

    private lateinit var trafficTracker: TrafficTracker

    @Before
    fun setup() =
        runTest {
            whenever(context.getSystemService(Context.NETWORK_STATS_SERVICE)).thenReturn(networkStatsManager)
            whenever(settingsRepository.getLastDataSubId()).thenReturn(lastDataSubIdFlow)
            whenever(simRepository.getIdentity(any())).thenReturn("test_id")
            whenever(simRepository.getSimOperator(any())).thenReturn(flowOf("Test Operator"))

            // Mock NetworkStats bucket
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1000L)
            whenever(bucket.txBytes).thenReturn(500L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket)

            trafficTracker =
                TrafficTracker(
                    context,
                    simRepository,
                    settingsRepository,
                    permissionManager,
                    testScope,
                )
        }

    @Test
    fun `test SIM swap resets baseline`() =
        runTest {
            // Prepare: last known subId was 1
            lastDataSubIdFlow.value = 1

            // Act: current subId is 2
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.subscriptionId).thenReturn(2)
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            trafficTracker.updateTrafficUsage()

            // Assert: lastTotalBytes should be set (resetting baseline) for the new identity
            verify(simRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
            verify(settingsRepository).setLastDataSubId(2)
        }

    @Test
    fun `test fallback to single active SIM when default subId is missing`() =
        runTest {
            // Arrange: No default data subId
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            
            // SIM 0 is active, SIM 1 is empty
            whenever(simRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simRepository.getSimOperator("sim_0")).thenReturn(flowOf("MTS"))
            
            whenever(simRepository.getIdentity(1)).thenReturn("sim_1")
            whenever(simRepository.getSimOperator("sim_1")).thenReturn(flowOf("")) // empty/inactive

            // Act
            trafficTracker.updateTrafficUsage()

            // Assert: it should have used sim_0 as fallback
            verify(simRepository).getLastTotalBytes(eq("sim_0"))
        }
}
