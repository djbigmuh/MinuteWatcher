package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.NoOpLogger
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerTest {
    private val networkStatsProvider: NetworkStatsProvider = mock()
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
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                callSessionRepository,
                permissionManager,
                timeProvider,
                networkStatsProvider,
                TestScope(testDispatcher),
            )
    }

    @Test
    fun `updateTrafficUsage initializes baseline on first run`() =
        runTest(testDispatcher) {
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
            verify(callSessionRepository).setLastDataSubId(1)
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `processTrafficUpdate handles timeout reading lastTotalBytes without resetting baseline`() =
        runTest(testDispatcher) {
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(2000L))

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
            whenever(networkStatsProvider.queryMobileBytes(any(), any()))
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
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(35000L))

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            val deltaMb = (34000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageRepository).incrementTrafficUsed(eq("sim_0"), eq(deltaMb))
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(35000L))
        }

    @Test
    fun `updateTrafficUsage handles network stats unavailable`() =
        runTest(testDispatcher) {
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

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

            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
        }

    @Test
    fun `updateTrafficUsage handles SIM swap by resetting baseline`() =
        runTest(testDispatcher) {
            // First run: SubId 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1000L))

            // SIM swap: SubId 2
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))

            tracker.updateTrafficUsage()

            // Should reset baseline for the new SIM identity
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(1500L))
            verify(callSessionRepository).setLastDataSubId(2)
        }

    @Test
    fun `updateTrafficUsage retries baseline reset when stats become available after SIM swap`() =
        runTest(testDispatcher) {
            // 1. Initial state
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap. Setup sequence: 1st call fails, 2nd call succeeds.
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)

            var callCount = 0
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenAnswer {
                callCount++
                if (callCount == 1) StatsResult.Unavailable else StatsResult.Success(2000L)
            }

            // Attempt 1: Stats unavailable
            tracker.updateTrafficUsage()
            verify(simUsageResetRepository, never()).setLastTotalBytes(eq("sim_1"), any())

            // Attempt 2: Stats finally available
            tracker.updateTrafficUsage()

            // Verify baseline IS set for sim_1 now
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(2000L))

            // Total calls: 1 (initial) + 2 (swap retry) = 3
            verify(networkStatsProvider, times(3)).queryMobileBytes(any(), any())
        }

    @Test
    fun `updateTrafficUsage continues to retry baseline reset if stats remain unavailable`() =
        runTest(testDispatcher) {
            // 1. Initial state
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap. Setup sequence: 1st and 2nd calls fail, 3rd call succeeds.
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)

            var callCount = 0
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenAnswer {
                callCount++
                if (callCount <= 2) StatsResult.Unavailable else StatsResult.Success(3000L)
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
            verify(networkStatsProvider, times(4)).queryMobileBytes(any(), any())
        }

    @Test
    fun `resetBaseline initializes storage correctly`() =
        runTest(testDispatcher) {
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))

            tracker.resetBaseline("sim_0")

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `resetBaseline sets error status on unexpected exception`() =
        runTest(testDispatcher) {
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            tracker.resetBaseline("sim_0")

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
            assertEquals("Stats unavailable", status.message)
        }

    @Test
    fun `updateTrafficUsage respects small deltas`() =
        runTest(testDispatcher) {
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(2000L)) // 1000 bytes delta
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
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(500L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(500L))
        }

    @Test
    fun `test recover updates status`() =
        runTest(testDispatcher) {
            // Setup valid bucket for updateTrafficUsage inside recover()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            // Act
            tracker.recover()
            advanceUntilIdle()

            // Assert
            assertEquals(TrackingStatus.Active, tracker.status.value)
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
        }

    @Test
    fun `handleSimChange does not update lastDataSubId if stats are unavailable`() =
        runTest(testDispatcher) {
            // 1. Initial state: SubId 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)

            // 2. SIM swap to SubId 2, but stats are unavailable
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            // Act: First attempt at SIM change
            tracker.updateTrafficUsage()

            // Assert: baseline not set, lastDataSubId NOT updated to 2
            verify(simUsageResetRepository, never()).setLastTotalBytes(eq("sim_1"), any())
            verify(callSessionRepository, times(0)).setLastDataSubId(2)

            // 3. Stats become available
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(2000L))

            // Act: Second attempt at updateTrafficUsage
            tracker.updateTrafficUsage()

            // Assert: SIM change is still detected (isSimChanged=true) because lastDataSubId was not updated
            // Baseline is finally set for sim_1
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(2000L))
            verify(callSessionRepository).setLastDataSubId(2)
        }

    @Test
    fun `getCurrentMobileBytes uses lookback window on first run`() =
        runTest(testDispatcher) {
            val now = timeProvider.now()
            val lookbackStart = now - (Constants.Traffic.LOOKBACK_DAYS * Constants.MILLIS_PER_DAY)

            // Usage with lastResetDate = 0
            whenever(
                simUsageRepository.getSimUsage("sim_0"),
            ).thenReturn(flowOf(createSimUsage("sim_0", lastResetDate = 0L)))
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(flowOf(-1L))
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(100L))

            tracker.updateTrafficUsage()

            // Verify query was called with lookbackStart
            verify(networkStatsProvider).queryMobileBytes(eq(lookbackStart), eq(now))
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
