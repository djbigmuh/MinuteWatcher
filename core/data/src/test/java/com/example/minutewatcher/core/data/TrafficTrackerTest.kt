package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
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

        val activeSims =
            listOf(
                ActiveSimInfo(
                    identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID),
                    slotIndex = 0,
                    subscriptionId = 1,
                    displayName = "SIM 1",
                    carrierName = "Carrier 1",
                    mccMnc = "25001",
                    isDefaultData = true,
                    isDefaultVoice = true,
                ),
                ActiveSimInfo(
                    identity = SimIdentity("sim_1", 2, 1, IdentitySource.ICCID),
                    slotIndex = 1,
                    subscriptionId = 2,
                    displayName = "SIM 2",
                    carrierName = "Carrier 2",
                    mccMnc = "25002",
                    isDefaultData = false,
                    isDefaultVoice = false,
                ),
            )
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))

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
    fun `updateTrafficUsage sets Idle when no active SIM`() =
        runTest(testDispatcher) {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            tracker.updateTrafficUsage()

            assertEquals(TrackingStatus.Idle, tracker.status.value)
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
            // Now we expect a baseline reset to -1L on failure
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(-1L))
            // skipDelta is true, so status is Active
            assertEquals(TrackingStatus.Active, tracker.status.value)

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
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(-1L))
            // skipDelta is true, so status is Active
            assertEquals(TrackingStatus.Active, tracker.status.value)

            // Attempt 2: Stats still unavailable
            tracker.updateTrafficUsage()
            // skipDelta is false now, so processTrafficUpdate -> handleBaselineRetry is called
            assertTrue(tracker.status.value is TrackingStatus.Error)
            assertEquals("Stats unavailable, retry pending", (tracker.status.value as TrackingStatus.Error).message)
            // It should be Error status because it's in baselineNeedsRetry.
            // But it won't call setLastTotalBytes again until stats are Success.
            verify(simUsageResetRepository, times(1)).setLastTotalBytes(eq("sim_1"), eq(-1L))

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
            whenever(networkStatsProvider.queryMobileBytes(any(), any()))
                .thenReturn(StatsResult.Success(2000L)) // 1000 bytes delta
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
    fun `handleSimChange updates lastDataSubId even if stats are unavailable`() =
        runTest(testDispatcher) {
            // 1. Initial state: SubId 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)

            // 2. SIM swap to SubId 2, but stats are unavailable
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            // Attempt: Stats unavailable
            tracker.updateTrafficUsage()

            // Assert: baseline set to -1L, AND lastDataSubId UPDATED to 2
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(-1L))
            assertEquals(TrackingStatus.Active, tracker.status.value)

            val inOrder = inOrder(networkStatsProvider, callSessionRepository)
            // Должен быть вызов из updateTrafficUsage (initial)
            inOrder.verify(networkStatsProvider).queryMobileBytes(any(), any())
            inOrder.verify(callSessionRepository).setLastDataSubId(1)
            // Затем вызов из handleSimChange (swap attempt)
            inOrder.verify(networkStatsProvider).queryMobileBytes(any(), any())
            inOrder.verify(callSessionRepository).setLastDataSubId(2)

            // 3. Stats become available
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(2000L))

            // Act: Second attempt at updateTrafficUsage
            tracker.updateTrafficUsage()

            // Assert: Baseline is finally set for sim_1
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(2000L))
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

    @Test
    fun `handleSimChange clears previous retry entries`() =
        runTest(testDispatcher) {
            // 1. Initial state: SubId 1 (sim_0)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap to SubId 2 (sim_1), but stats are unavailable
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            tracker.updateTrafficUsage()

            // Verify sim_1 is in baselineNeedsRetry
            val field = TrafficTracker::class.java.getDeclaredField("baselineNeedsRetry")
            field.isAccessible = true
            val map = field[tracker] as Map<*, *>
            assertTrue("sim_1 should be in retry map", map.containsKey("sim_1"))

            // 3. SIM swap back to SubId 1 (sim_0)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(3000L))

            tracker.updateTrafficUsage()

            // Assert: sim_1 should have been cleared even though it's still in "activeSims"
            assertFalse("sim_1 should be cleared from retry map", map.containsKey("sim_1"))
        }

    @Test
    fun `handleSimChange forces baseline reset when stats are unavailable`() =
        runTest(testDispatcher) {
            // 1. Initial state: SubId 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap to SubId 2, stats unavailable
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            tracker.updateTrafficUsage()

            // Verify setLastTotalBytes(sim_1, -1L) called AND updateLastDataSubId(2) called
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(-1L))
            verify(callSessionRepository).setLastDataSubId(2)
        }

    @Test
    fun `handleSimChange breaks retry cycle when limit is exceeded`() =
        runTest(testDispatcher) {
            // 1. Initial state
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap to SubId 2
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            // Manually inject retry count to skip the 1..4 iterations
            val field = TrafficTracker::class.java.getDeclaredField("baselineNeedsRetry")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field[tracker] as MutableMap<String, Int>
            map["sim_1"] = 5 // MAX_BASELINE_RETRIES

            // Act
            tracker.updateTrafficUsage()

            // Assert: handleRetryLimitExceeded should have been called
            // verify setLastTotalBytes(sim_1, -1L) and updateLastDataSubId(2)
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(-1L))
            verify(callSessionRepository).setLastDataSubId(2)

            // Check that next update doesn't trigger SIM change again
            tracker.updateTrafficUsage()
            verify(callSessionRepository, times(1)).setLastDataSubId(2) // Still 1 total
        }

    @Test
    fun `updateTrafficUsage calls observeActiveSims only once and checks permissions first`() =
        runTest(testDispatcher) {
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)

            tracker.updateTrafficUsage()

            // observeActiveSims should NOT be called if permissions are missing (it's after checkPermissions)
            verify(simIdentityRepository, never()).observeActiveSims()

            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            tracker.updateTrafficUsage()

            // Now it should be called exactly once
            verify(simIdentityRepository, times(1)).observeActiveSims()
        }

    @Test
    fun `updateTrafficUsage does not skip delta after identity timeout`() =
        runTest(testDispatcher) {
            // 1. Successful first run to set initial state
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)

            // 2. Second run: observeActiveSims times out
            whenever(simIdentityRepository.observeActiveSims())
                .thenReturn(MutableSharedFlow()) // Never emits
            tracker.updateTrafficUsage()

            assertTrue(tracker.status.value is TrackingStatus.Error)
            assertEquals(
                "Identity read timeout in TrafficTracker",
                (tracker.status.value as TrackingStatus.Error).message,
            )

            // 3. Third run: observeActiveSims succeeds, same SIM
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID),
                        slotIndex = 0,
                        subscriptionId = 1,
                        displayName = "SIM 1",
                        carrierName = "Carrier 1",
                        mccMnc = "25001",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(50000L))

            tracker.updateTrafficUsage()

            // Verify skipDelta did NOT trigger: usage should be incremented (50000 - 1000 = 49000)
            val deltaMb = (49000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageRepository).incrementTrafficUsed(eq("sim_0"), eq(deltaMb))
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(50000L))
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `getActiveSimsAndCleanup timeout does not reset inMemoryLastDataSubId`() =
        runTest(testDispatcher) {
            // 1. Initial success to set inMemoryLastDataSubId to 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            val field = TrafficTracker::class.java.getDeclaredField("inMemoryLastDataSubId")
            field.isAccessible = true
            val atomicId = field[tracker] as java.util.concurrent.atomic.AtomicInteger
            assertEquals(1, atomicId.get())

            // 2. Timeout on observeActiveSims
            whenever(simIdentityRepository.observeActiveSims())
                .thenReturn(MutableSharedFlow())
            tracker.updateTrafficUsage()

            // 3. Verify inMemoryLastDataSubId is STILL 1, not -1
            assertEquals("inMemoryLastDataSubId should NOT be reset on timeout", 1, atomicId.get())
        }

    @Test
    fun `updateTrafficUsage reads fresh lastTotalBytes after resetBaseline due to serialization`() =
        runTest(testDispatcher) {
            var currentStoredValue = 1000L

            // Mock repository to reflect updates in-memory
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenAnswer {
                flowOf(currentStoredValue)
            }
            whenever(simUsageResetRepository.setLastTotalBytes(eq("sim_0"), any())).thenAnswer {
                currentStoredValue = it.getArgument(1)
            }

            // Mock network stats: first call sets 5000, second call adds 100000 (total 105000)
            var queryCount = 0
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenAnswer {
                queryCount++
                StatsResult.Success(if (queryCount == 1) 5000L else 105000L)
            }

            // Launch both. simChangeMutex ensures they don't race.
            // resetBaseline will take the lock first and update baseline to 5000.
            // updateTrafficUsage will wait, then read 5000 as baseline, and calculate delta (105000 - 5000 = 100000).
            launch { tracker.resetBaseline("sim_0") }
            launch { tracker.updateTrafficUsage() }

            advanceUntilIdle()

            // Verify delta was calculated from 5000, not the initial 1000
            val expectedDeltaMb = (100000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageRepository).incrementTrafficUsed(eq("sim_0"), eq(expectedDeltaMb))
            assertEquals(105000L, currentStoredValue)
        }

    @Test
    fun `ensureInMemoryLastDataSubId avoids redundant DataStore reads`() =
        runTest(testDispatcher) {
            // Setup: initial state with sim_0
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            // Mock DataStore read for lastDataSubId
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(1))

            // First call: should read from DataStore
            tracker.updateTrafficUsage()
            verify(callSessionRepository, times(1)).getLastDataSubId()

            // Second call: should NOT read from DataStore again
            tracker.updateTrafficUsage()
            verify(callSessionRepository, times(1)).getLastDataSubId()
        }

    @Test
    fun `updateTrafficUsage sets error status on identity timeout`() =
        runTest(testDispatcher) {
            // Arrange: observeActiveSims never emits
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())

            // Act
            tracker.updateTrafficUsage()

            // Assert
            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals("Identity read timeout in TrafficTracker", (status as TrackingStatus.Error).message)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, status.type)
        }

    @Test
    fun `getActiveSimsAndCleanup timeout does not clear baselineNeedsRetry`() =
        runTest(testDispatcher) {
            // Arrange: add an entry to baselineNeedsRetry
            val identity = "sim_0"
            val field = TrafficTracker::class.java.getDeclaredField("baselineNeedsRetry")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field[tracker] as MutableMap<String, Int>
            map[identity] = 1

            // Mock timeout
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())

            // Act
            tracker.updateTrafficUsage()

            // Assert
            assertTrue("baselineNeedsRetry should NOT be cleared on timeout", map.containsKey(identity))
        }

    @Test
    fun `resetBaseline initializes inMemoryLastDataSubId and avoids subsequent DataStore read`() =
        runTest(testDispatcher) {
            // Setup: sim_0 is active default data SIM (subId 1)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(flowOf(1000L))

            // resetBaseline will call updateLastDataSubId(1) which sets inMemoryLastDataSubId
            tracker.resetBaseline("sim_0")

            // updateTrafficUsage should now see it's initialized and NOT read from DataStore
            tracker.updateTrafficUsage()

            verify(callSessionRepository, never()).getLastDataSubId()
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
        trafficUsedMb = 0f,
        trafficLimitMb = 0,
        manualAdjustment = 0,
        trafficAdjustmentGb = 0f,
        autoReset = true,
        autoResetDay = 1,
        lastResetDate = lastResetDate,
        rolloverEnabled = false,
    )
}
