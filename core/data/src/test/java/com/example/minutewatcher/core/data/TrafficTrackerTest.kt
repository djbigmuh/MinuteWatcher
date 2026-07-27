package com.example.minutewatcher.core.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
    fun testSimSwapResetsBaseline() =
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
    fun testTrafficIncrementOnDeltaDetection() =
        runTest {
            // Arrange
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.subscriptionId).thenReturn(1)
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            // lastTotalBytes was 1000, now it's 1500 (from setup) -> delta 500 bytes
            whenever(simRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            // Act
            trafficTracker.updateTrafficUsage()

            // Assert
            val deltaMb = 500f / (1024 * 1024)
            verify(simRepository).incrementTrafficUsed(eq("test_id"), eq(deltaMb))
            verify(simRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
        }

    @Test
    fun testTrafficTrackerHandlesMissingPermission() =
        runTest {
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenThrow(SecurityException())

            // Need an active SIM to trigger the permission check in updateTrafficUsage
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.subscriptionId).thenReturn(1)
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            trafficTracker.updateTrafficUsage()
            runCurrent()

            val status = trafficTracker.status.first()
            assert(status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun testFallbackToSingleActiveSimWhenDefaultSubIdIsMissing() =
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
            val flow = verify(simRepository).getLastTotalBytes(eq("sim_0"))
            println("Verified call to getLastTotalBytes with $flow")
        }

    @Test
    fun testUpdateTraffic_NoTargetSim_SetsIdleStatus() =
        runTest {
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            whenever(simRepository.getIdentity(any())).thenReturn("inactive")
            whenever(simRepository.getSimOperator(any())).thenReturn(flowOf(""))

            trafficTracker.updateTrafficUsage()

            assertEquals(TrackingStatus.Idle, trafficTracker.status.first())
        }

    @Test
    fun testUpdateTraffic_MissingPermission_SetsPermissionMissingStatus() =
        runTest {
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenThrow(SecurityException())

            // Need an active SIM to trigger the permission check in updateTrafficUsage
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.subscriptionId).thenReturn(1)
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            trafficTracker.updateTrafficUsage()
            runCurrent()

            val status = trafficTracker.status.first()
            assert(status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun testUpdateTraffic_SecurityException_SetsPermissionMissingStatus() =
        runTest {
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenThrow(SecurityException())

            trafficTracker.updateTrafficUsage()
            runCurrent()

            val status = trafficTracker.status.first()
            assert(status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun testUpdateTraffic_SimSwap_SetsIdleStatus() =
        runTest {
            // Prepare: last known subId was 1
            lastDataSubIdFlow.value = 1

            // Act: current subId is 2 (Swap)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.subscriptionId).thenReturn(2)
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))

            trafficTracker.updateTrafficUsage()

            // Should be Idle after swap detection to avoid mixed stats
            assertEquals(TrackingStatus.Idle, trafficTracker.status.first())
            verify(simRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
        }

    @Test
    fun testResetBaseline_SecurityException_SetsPermissionMissingStatus() =
        runTest {
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenThrow(SecurityException())

            trafficTracker.resetBaseline("test_id")
            runCurrent()

            val status = trafficTracker.status.first()
            assert(status is TrackingStatus.Error && status.type == TrackingStatus.ErrorType.PERMISSION_MISSING)
        }

    @Test
    fun testResetBaseline_UpdatesRepository() =
        runTest {
            // Arrange: Bucket returns 1500 bytes (setup)
            val identity = "reboot_id"

            // Act
            trafficTracker.resetBaseline(identity)

            // Assert
            verify(simRepository).setLastTotalBytes(eq(identity), eq(1500L))
            assertEquals(TrackingStatus.Active, trafficTracker.status.first())
        }
}
