package com.example.minutewatcher.core.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.isNull
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.NoOpLogger

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerTest {
    private val context: Context = mock()
    private val networkStatsManager: NetworkStatsManager = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1700000000000L)
    private val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    private lateinit var tracker: TrafficTracker

    @Before
    fun setup() {
        AppLogger.init(mock(), NoOpLogger())
        whenever(context.getSystemService(Context.NETWORK_STATS_SERVICE)).thenReturn(networkStatsManager)

        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)

        // Match default data subId with an active subscription
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        val mockSubInfo = mock<android.telephony.SubscriptionInfo>()
        whenever(mockSubInfo.subscriptionId).thenReturn(1)
        whenever(mockSubInfo.simSlotIndex).thenReturn(0)

        val mockSubInfo2 = mock<android.telephony.SubscriptionInfo>()
        whenever(mockSubInfo2.subscriptionId).thenReturn(2)
        whenever(mockSubInfo2.simSlotIndex).thenReturn(1)

        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo, mockSubInfo2))

        whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(-1))

        runTest(testDispatcher) {
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")
        }

        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

        whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0", "sim_1")))
        whenever(simIdentityRepository.getSimOperator(any())).thenReturn(flowOf("Test Operator"))
        whenever(simUsageRepository.getSimUsage(any())).thenReturn(flowOf(createSimUsage("sim_0")))
        whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(flowOf(createSimUsage("sim_0")))
        whenever(simUsageRepository.getSimUsage("sim_1")).thenReturn(flowOf(createSimUsage("sim_1")))

        tracker =
            TrafficTracker(
                context,
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                callSessionRepository,
                permissionManager,
                timeProvider,
                TestScope(testDispatcher),
            )
    }

    @Test
    fun `updateTrafficUsage initializes baseline on first run`() =
        runTest(testDispatcher) {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1000L)
            whenever(bucket.txBytes).thenReturn(500L)

            whenever(
                networkStatsManager.querySummaryForDevice(
                    anyInt(),
                    anyOrNull(),
                    anyLong(),
                    anyLong(),
                ),
            ).thenReturn(bucket)

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
            verify(callSessionRepository).setLastDataSubId(1)
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `processTrafficUpdate handles timeout reading lastTotalBytes without resetting baseline`() =
        runTest(testDispatcher) {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(2000L)
            whenever(bucket.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket)

            // lastTotalBytes never emits
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(MutableSharedFlow())

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals("Timeout reading lastTotalBytes", (status as TrackingStatus.Error).message)

            // Should NOT call setLastTotalBytes (which would happen if baseline was reset due to ?: -1L bug)
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
        }

    @Test
    fun `processTrafficUpdate handles timeout reading simUsage`() =
        runTest(testDispatcher) {
            // simUsage never emits
            whenever(simUsageRepository.getSimUsage(any())).thenReturn(MutableSharedFlow())

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals("Timeout reading simUsage", (status as TrackingStatus.Error).message)

            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
        }

    @Test
    fun `updateTrafficUsage handles SecurityException gracefully`() =
        runTest(testDispatcher) {
            whenever(networkStatsManager.querySummaryForDevice(anyInt(), anyOrNull(), anyLong(), anyLong()))
                .thenThrow(SecurityException("No permission"))

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `updateTrafficUsage reports error when Usage Access is missing`() =
        runTest(testDispatcher) {
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `updateTrafficUsage increments usage based on delta`() =
        runTest(testDispatcher) {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(25000L)
            whenever(bucket.txBytes).thenReturn(10000L)

            whenever(
                networkStatsManager.querySummaryForDevice(
                    anyInt(),
                    anyOrNull(),
                    anyLong(),
                    anyLong(),
                ),
            ).thenReturn(bucket)

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            val deltaMb = (34000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageRepository).incrementTrafficUsed(eq("sim_0"), eq(deltaMb))
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(35000L))
        }

    @Test
    fun `updateTrafficUsage handles network stats unavailable`() =
        runTest(testDispatcher) {
            whenever(
                networkStatsManager.querySummaryForDevice(
                    any(),
                    anyOrNull(),
                    any(),
                    any(),
                ),
            ).thenThrow(RuntimeException("Stats unavailable"))

            tracker.updateTrafficUsage()

            assertEquals(
                TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Stats unavailable"),
                tracker.status.value,
            )
        }

    @Test
    fun `findSingleActiveSimSlot returns slot when only one SIM has valid operator`() =
        runTest(testDispatcher) {
            // No default data subId
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)

            // Slot 0 active
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.getSimOperator("sim_0")).thenReturn(flowOf("MTS"))

            // Slot 1 inactive
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")
            whenever(simIdentityRepository.getSimOperator("sim_1")).thenReturn(flowOf("")) // empty/inactive

            whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(flowOf(createSimUsage("sim_0")))

            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1500L)
            whenever(bucket.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
        }

    @Test
    fun `updateTrafficUsage handles SIM swap by resetting baseline`() =
        runTest(testDispatcher) {
            // First run: SubId 1
            val bucket1 = mock<NetworkStats.Bucket>()
            whenever(bucket1.rxBytes).thenReturn(1000L)
            whenever(bucket1.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket1)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1000L))

            // SIM swap: SubId 2
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            val bucket2 = mock<NetworkStats.Bucket>()
            whenever(bucket2.rxBytes).thenReturn(1500L)
            whenever(bucket2.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket2)

            tracker.updateTrafficUsage()

            // Should reset baseline for the new SIM identity
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(1500L))
            verify(callSessionRepository).setLastDataSubId(2)
        }

    @Test
    fun `updateTrafficUsage retries baseline reset when stats become available after SIM swap`() =
        runTest(testDispatcher) {
            // 1. Initial state
            val bucket1 = mock<NetworkStats.Bucket>()
            whenever(bucket1.rxBytes).thenReturn(1000L)
            whenever(bucket1.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket1)

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap. Setup sequence: 1st call fails, 2nd call succeeds.
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)

            val bucket2 = mock<NetworkStats.Bucket>()
            whenever(bucket2.rxBytes).thenReturn(2000L)
            whenever(bucket2.txBytes).thenReturn(0L)

            var callCount = 0
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenAnswer {
                callCount++
                if (callCount == 1) throw RuntimeException("Stats unavailable")
                bucket2
            }

            // Attempt 1: Stats unavailable
            tracker.updateTrafficUsage()
            verify(simUsageResetRepository, never()).setLastTotalBytes(eq("sim_1"), any())

            // Attempt 2: Stats finally available
            tracker.updateTrafficUsage()

            // Verify baseline IS set for sim_1 now
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(2000L))

            // Total calls: 1 (initial) + 2 (swap retry) = 3
            verify(networkStatsManager, times(3)).querySummaryForDevice(any(), anyOrNull(), any(), any())
        }

    @Test
    fun `updateTrafficUsage continues to retry baseline reset if stats remain unavailable`() =
        runTest(testDispatcher) {
            // 1. Initial state
            val bucket1 = mock<NetworkStats.Bucket>()
            whenever(bucket1.rxBytes).thenReturn(1000L)
            whenever(bucket1.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket1)

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap. Setup sequence: 1st and 2nd calls fail, 3rd call succeeds.
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)

            val bucket3 = mock<NetworkStats.Bucket>()
            whenever(bucket3.rxBytes).thenReturn(3000L)
            whenever(bucket3.txBytes).thenReturn(0L)

            var callCount = 0
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenAnswer {
                callCount++
                if (callCount <= 2) throw RuntimeException("Stats unavailable")
                bucket3
            }

            // Attempt 1: Stats unavailable
            tracker.updateTrafficUsage()
            verify(simUsageResetRepository, never()).setLastTotalBytes(eq("sim_1"), any())

            // Attempt 2: Stats still unavailable
            tracker.updateTrafficUsage()
            verify(simUsageResetRepository, never()).setLastTotalBytes(eq("sim_1"), any())

            // Attempt 3: Stats finally become available
            tracker.updateTrafficUsage()

            // Verify baseline IS set
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(3000L))

            // Total calls: 1 (initial) + 3 (swap retries) = 4
            verify(networkStatsManager, times(4)).querySummaryForDevice(any(), anyOrNull(), any(), any())
        }

    @Test
    fun `resetBaseline initializes storage correctly`() =
        runTest(testDispatcher) {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1500L)
            whenever(bucket.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket)

            tracker.resetBaseline("sim_0")

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `resetBaseline sets error status on unexpected exception`() =
        runTest(testDispatcher) {
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any()))
                .thenThrow(RuntimeException("Unexpected error"))

            tracker.resetBaseline("sim_0")

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
            assertEquals("Unexpected error", status.message)
        }

    @Test
    fun `updateTrafficUsage respects small deltas`() =
        runTest(testDispatcher) {
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(2000L) // 1000 bytes delta
            whenever(bucket.txBytes).thenReturn(0L)

            whenever(
                networkStatsManager.querySummaryForDevice(
                    eq(0), // NetworkCapabilities.TRANSPORT_CELLULAR
                    isNull(),
                    anyLong(),
                    anyLong(),
                ),
            ).thenReturn(bucket)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            // 1000 bytes is < 0.01 MB (~10KB), should not increment usage but status is Active
            verify(simUsageRepository, never()).incrementTrafficUsed(any(), any())
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `updateTrafficUsage handles reboot or overflow`() =
        runTest(testDispatcher) {
            // lastTotalBytes = 1000, current = 500 (reboot)
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(500L)
            whenever(bucket.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(500L))
        }

    @Test
    fun `test recover updates status`() =
        runTest(testDispatcher) {
            // Setup valid bucket for updateTrafficUsage inside recover()
            val bucket = mock<NetworkStats.Bucket>()
            whenever(bucket.rxBytes).thenReturn(1500L)
            whenever(bucket.txBytes).thenReturn(0L)
            whenever(networkStatsManager.querySummaryForDevice(any(), anyOrNull(), any(), any())).thenReturn(bucket)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            // Act
            tracker.recover()
            advanceUntilIdle()

            // Assert
            assertEquals(TrackingStatus.Active, tracker.status.value)
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
        }

    @Suppress("SameParameterValue") // Helper for creating test data with defaults.
    private fun createSimUsage(
        id: String,
        lastResetDate: Long = 0L,
    ) = SimUsage(
        identity = id,
        slotIndex = 0,
        subscriptionId = 1,
        operator = "Test Operator",
        minutesUsed = 0,
        minutesLimit = 0,
        trafficUsed = 0f,
        trafficLimitMb = 0,
        manualAdjustment = 0,
        trafficAdjustment = 0f,
        autoReset = true,
        autoResetDay = 1,
        lastResetDate = lastResetDate,
        rolloverEnabled = false,
    )
}
