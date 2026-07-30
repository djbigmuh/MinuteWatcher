package com.example.minutewatcher.core.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.android.util.PermissionManager
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.isNull
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerTest {
    private val context: Context = mock()
    private val networkStatsManager: NetworkStatsManager = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1000L)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var tracker: TrafficTracker

    @Before
    fun setup() {
        whenever(context.getSystemService(Context.NETWORK_STATS_SERVICE)).thenReturn(networkStatsManager)
        
        // Match default data subId with an active subscription
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        val mockSubInfo = mock<android.telephony.SubscriptionInfo>()
        whenever(mockSubInfo.subscriptionId).thenReturn(1)
        whenever(mockSubInfo.simSlotIndex).thenReturn(0)
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

        whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(-1))

        runBlocking {
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")
            whenever(simIdentityRepository.getIdentity(any())).thenReturn("test_id")
        }
        whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("test_id")))
        whenever(simIdentityRepository.getSimOperator(any())).thenReturn(flowOf("Test Operator"))

        tracker =
            TrafficTracker(
                context,
                simIdentityRepository,
                simUsageRepository,
                callSessionRepository,
                permissionManager,
                timeProvider,
                testScope,
            )
    }

    @Test
    fun `updateTrafficUsage initializes baseline on first run`() =
        runTest {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1000L)
            whenever(bucket.txBytes).thenReturn(500L)

            whenever(
                networkStatsManager.querySummaryForDevice(
                    eq(NetworkCapabilities.TRANSPORT_CELLULAR),
                    isNull(),
                    anyLong(),
                    anyLong(),
                ),
            ).thenReturn(bucket)

            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
            verify(callSessionRepository).setLastDataSubId(1)
            assertEquals(TrackingStatus.Active, tracker.status.first())
        }

    @Test
    fun `updateTrafficUsage increments usage based on delta`() =
        runTest {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1000L)
            whenever(bucket.txBytes).thenReturn(500L)

            whenever(
                networkStatsManager.querySummaryForDevice(
                    anyInt(),
                    isNull(),
                    anyLong(),
                    anyLong(),
                ),
            ).thenReturn(bucket)

            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            val deltaMb = 500f / Constants.BYTES_PER_MB
            verify(simUsageRepository).incrementTrafficUsed(eq("test_id"), eq(deltaMb))
            verify(simUsageRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
        }

    @Test
    fun `updateTrafficUsage handles network stats unavailable`() =
        runTest {
            whenever(
                networkStatsManager.querySummaryForDevice(
                    anyInt(),
                    isNull(),
                    anyLong(),
                    anyLong(),
                ),
            ).thenThrow(RuntimeException("Stats unavailable"))

            tracker.updateTrafficUsage()

            assertEquals(TrackingStatus.Idle, tracker.status.first())
        }

    @Test
    fun `updateTrafficUsage reports permission error when READ_PHONE_STATE is missing`() =
        runTest {
            // 1. Mock missing permission
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(false)
            // 2. This makes getTargetSimInfo return null
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            
            // 3. Act
            tracker.updateTrafficUsage()

            // 4. Assert
            val status = tracker.status.first()
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `findSingleActiveSimSlot returns slot when only one SIM has valid operator`() =
        runTest {
            // No default data subId
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)

            // Slot 0 active
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.getSimOperator("sim_0")).thenReturn(flowOf("MTS"))

            // Slot 1 inactive
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")
            whenever(simIdentityRepository.getSimOperator("sim_1")).thenReturn(flowOf("")) // empty/inactive

            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1500L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), any(), anyLong(), anyLong())).thenReturn(bucket)
            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageRepository).getLastTotalBytes(eq("sim_0"))
        }

    @Test
    fun `updateTrafficUsage handles SIM swap by resetting baseline`() =
        runTest {
            // First run: SubId 1
            val bucket1 = mock<NetworkStats.Bucket>()
            whenever(bucket1.rxBytes).thenReturn(1000L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket1)
            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)

            // SIM swap: SubId 2
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            val bucket2 = mock<NetworkStats.Bucket>()
            whenever(bucket2.rxBytes).thenReturn(1500L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket2)

            tracker.updateTrafficUsage()

            // Should reset baseline for the new SIM identity
            verify(simUsageRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
            verify(callSessionRepository).setLastDataSubId(2)
        }

    @Test
    fun `resetBaseline initializes storage correctly`() =
        runTest {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1500L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket)

            tracker.resetBaseline("test_id")

            verify(simUsageRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
            assertEquals(TrackingStatus.Active, tracker.status.first())
        }

    @Test
    fun `updateTrafficUsage respects small deltas`() =
        runTest {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1000L + 50L) // only 50 bytes delta

            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket)
            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            // 50 bytes is < 0.0001 MB, should not increment usage but status is Active
            verify(simUsageRepository, never()).incrementTrafficUsed(any(), any())
            assertEquals(TrackingStatus.Active, tracker.status.first())
        }

    @Test
    fun `updateTrafficUsage handles reboot or overflow`() =
        runTest {
            // lastTotalBytes = 1000, current = 500 (reboot)
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(500L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket)
            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            verify(simUsageRepository).setLastTotalBytes(eq("test_id"), eq(500L))
        }

    @Test
    fun `test recover updates status`() =
        runTest {
            // Setup valid bucket for updateTrafficUsage inside recover()
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1500L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket)
            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            // Act
            tracker.recover()
            testScope.advanceUntilIdle()

            // Assert
            assertEquals(TrackingStatus.Active, tracker.status.first())
            verify(simUsageRepository).setLastTotalBytes(eq("test_id"), eq(1500L))
        }

    @Test
    fun `updateTrafficUsage does not save -1 subId and detects SIM swap after temporary unknown state`() =
        runTest {
            // 1. Initial run with SubId 1
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            val bucket1 = mock<NetworkStats.Bucket>()
            whenever(bucket1.rxBytes).thenReturn(1000L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket1)
            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)

            // 2. Data SIM becomes unknown (-1)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            val bucket2 = mock<NetworkStats.Bucket>()
            whenever(bucket2.rxBytes).thenReturn(1100L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket2)
            whenever(simUsageRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            // Should NOT have called setLastDataSubId(-1)
            verify(callSessionRepository, never()).setLastDataSubId(-1)

            // 3. Data SIM changes to SubId 2
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            val mockSubInfo2 = mock<android.telephony.SubscriptionInfo>()
            whenever(mockSubInfo2.subscriptionId).thenReturn(2)
            whenever(mockSubInfo2.simSlotIndex).thenReturn(0)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo2))

            val bucket3 = mock<NetworkStats.Bucket>()
            whenever(bucket3.rxBytes).thenReturn(1200L)
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), isNull(), anyLong(), anyLong())).thenReturn(bucket3)

            tracker.updateTrafficUsage()

            // Should detect swap (1 -> 2) and call setLastDataSubId(2)
            verify(callSessionRepository).setLastDataSubId(2)
            // Baseline should be reset for identity at 1200L
            verify(simUsageRepository).setLastTotalBytes(eq("test_id"), eq(1200L))
        }
}



