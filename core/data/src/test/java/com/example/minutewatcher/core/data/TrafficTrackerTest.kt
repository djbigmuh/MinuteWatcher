package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
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
        initTestLogger()
        setupPermissionMocks()
        setupIdentityMocks()
        setupUsageMocks()

        val baselineManager =
            TrafficBaselineManager(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                permissionManager,
                timeProvider,
                networkStatsProvider,
            )
        val deltaCalculator = TrafficDeltaCalculator(simUsageResetRepository)
        val simChangeHandler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                simUsageResetRepository,
                baselineManager,
            )
        val targetResolver = TrafficTargetResolver(permissionManager)

        tracker =
            TrafficTracker(
                simIdentityRepository = simIdentityRepository,
                simUsageRepository = simUsageRepository,
                simUsageResetRepository = simUsageResetRepository,
                permissionManager = permissionManager,
                baselineManager = baselineManager,
                deltaCalculator = deltaCalculator,
                simChangeHandler = simChangeHandler,
                targetResolver = targetResolver,
                errorHandler = TrafficErrorHandler(),
                permissionChecker = TrafficPermissionChecker(permissionManager),
            )
    }

    private fun setupPermissionMocks() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        val mockSubInfo =
            mock<android.telephony.SubscriptionInfo> {
                on { subscriptionId } doReturn 1
                on { simSlotIndex } doReturn 0
            }
        val mockSubInfo2 =
            mock<android.telephony.SubscriptionInfo> {
                on { subscriptionId } doReturn 2
                on { simSlotIndex } doReturn 1
            }
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo, mockSubInfo2))
    }

    private fun setupIdentityMocks() {
        whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(-1))
        runBlocking {
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
    }

    private fun setupUsageMocks() {
        val sim0UsageFlow = flowOf(createSimUsage("sim_0"))
        whenever(simUsageRepository.getSimUsage(any())).thenReturn(sim0UsageFlow)
        val sim0UsageFlow2 = flowOf(createSimUsage("sim_0"))
        whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(sim0UsageFlow2)
        val sim1UsageFlow = flowOf(createSimUsage("sim_1"))
        whenever(simUsageRepository.getSimUsage("sim_1")).thenReturn(sim1UsageFlow)
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

            // lastTotalBytes never emits - use a flow that never emits
            // but doesn't block advanceUntilIdle forever if possible
            // Actually, for testing timeout we NEED it to not emit.
            whenever(
                simUsageResetRepository.getLastTotalBytes(any()),
            ).thenReturn(MutableSharedFlow())

            tracker.updateTrafficUsage()

            // Advance by more than LONG_TIMEOUT_MS
            testScheduler.advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS + 1000)
            testScheduler.runCurrent()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals("Timeout reading lastTotalBytes", (status as TrackingStatus.Error).message)

            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
        }

    @Test
    fun `processTrafficUpdate handles timeout reading simUsage`() =
        runTest(testDispatcher) {
            whenever(simUsageRepository.getSimUsage(any())).thenReturn(MutableSharedFlow())

            tracker.updateTrafficUsage()

            testScheduler.advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS + 1000)
            testScheduler.runCurrent()

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
    fun `updateTrafficUsage increments usage based on delta atomically`() =
        runTest(testDispatcher) {
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(35000L))

            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            val deltaMb = (34000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageResetRepository).incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(35000L))
        }

    @Test
    fun `updateTrafficUsage preserves delta when atomic update fails`() =
        runTest(testDispatcher) {
            // Setup initial state: baseline = 1000L
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            // 1. First attempt: stats = 35000L, delta = 34000L
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(35000L))

            // Mock atomic update to fail
            whenever(simUsageResetRepository.incrementTrafficAndSetBaseline(any(), any(), any()))
                .thenThrow(RuntimeException("Write failed"))

            tracker.updateTrafficUsage()

            // Verify failed attempt happened
            val deltaMb = (34000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageResetRepository).incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(35000L))

            // 2. Second attempt: stats still 35000L
            // If baseline was NOT updated in first attempt (due to atomicity), delta will still be 34000L.
            // In this mock-based test, we verify that the SECOND call to updateTrafficUsage
            // still sees lastTotalBytes as 1000L (which we provide via mock).

            whenever(simUsageResetRepository.incrementTrafficAndSetBaseline(any(), any(), any()))
                .thenReturn(Unit) // Succeed this time

            tracker.updateTrafficUsage()

            // Verify it was called again with same delta
            verify(simUsageResetRepository, times(2))
                .incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(35000L))
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

            val sim0UsageFlow3 = flowOf(createSimUsage("sim_0"))
            whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(sim0UsageFlow3)

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
            // We expect an error status and baseline is NOT set to -1L anymore
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
            assertTrue(tracker.status.value is TrackingStatus.Error)
            assertEquals("Stats unavailable", (tracker.status.value as TrackingStatus.Error).message)

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
            // Error status, baseline NOT reset
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
            assertTrue(tracker.status.value is TrackingStatus.Error)

            // Attempt 2: Stats still unavailable
            tracker.updateTrafficUsage()
            assertTrue(tracker.status.value is TrackingStatus.Error)
            assertEquals("Stats unavailable", (tracker.status.value as TrackingStatus.Error).message)
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())

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
            verify(simUsageResetRepository, never()).incrementTrafficAndSetBaseline(any(), any(), any())
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
            testScheduler.runCurrent()

            // Assert
            assertEquals(TrackingStatus.Active, tracker.status.value)
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_0"), eq(1500L))
        }

    @Test
    fun `handleSimChange does NOT update lastDataSubId if stats are unavailable`() =
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

            // Assert: baseline NOT set to -1L, AND lastDataSubId NOT UPDATED to 2
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
            assertTrue(tracker.status.value is TrackingStatus.Error)

            // verify setLastDataSubId(2) was never called
            verify(callSessionRepository, never()).setLastDataSubId(2)

            // 3. Stats become available
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(2000L))

            // Act: Second attempt at updateTrafficUsage
            tracker.updateTrafficUsage()

            // Assert: Baseline is finally set for sim_1
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(2000L))
            verify(callSessionRepository).setLastDataSubId(2)
        }

    @Test
    fun `getCurrentMobileBytes uses now on first run`() =
        runTest(testDispatcher) {
            val now = timeProvider.now()

            // Usage with lastResetDate = 0
            val sim0UsageFlow4 = flowOf(createSimUsage("sim_0"))
            whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(sim0UsageFlow4)
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(flowOf(-1L))
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(100L))

            tracker.updateTrafficUsage()

            // Verify query was called with now
            verify(networkStatsProvider).queryMobileBytes(eq(now), eq(now))
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
            val baselineManagerField = TrafficTracker::class.java.getDeclaredField("baselineManager")
            baselineManagerField.isAccessible = true
            val baselineManager = baselineManagerField[tracker] as TrafficBaselineManager

            val field = TrafficBaselineManager::class.java.getDeclaredField("baselineNeedsRetry")
            field.isAccessible = true
            val map = field[baselineManager] as Map<*, *>
            assertTrue("sim_1 should be in retry map", map.containsKey("sim_1"))

            // 3. SIM swap back to SubId 1 (sim_0)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(3000L))

            tracker.updateTrafficUsage()

            // Assert: sim_1 should have been cleared even though it's still in "activeSims"
            assertFalse("sim_1 should be cleared from retry map", map.containsKey("sim_1"))
        }

    @Test
    fun `handleSimChange does not reset baseline when stats are unavailable`() =
        runTest(testDispatcher) {
            // 1. Initial state: SubId 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            // 2. SIM swap to SubId 2, stats unavailable
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            tracker.updateTrafficUsage()

            // Verify status is Error, baseline NOT reset
            assertTrue(tracker.status.value is TrackingStatus.Error)
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
            verify(callSessionRepository, never()).setLastDataSubId(2)
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
            val baselineManagerField = TrafficTracker::class.java.getDeclaredField("baselineManager")
            baselineManagerField.isAccessible = true
            val baselineManager = baselineManagerField[tracker] as TrafficBaselineManager

            val field = TrafficBaselineManager::class.java.getDeclaredField("baselineNeedsRetry")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field[baselineManager] as MutableMap<String, Int>
            map["sim_1"] = 5 // MAX_BASELINE_RETRIES

            // Act
            tracker.updateTrafficUsage()

            // Assert: verify status is Error, baseline NOT reset
            assertTrue(tracker.status.value is TrackingStatus.Error)
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
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
            testScheduler.runCurrent()
            @Suppress("UnusedFlow")
            verify(simIdentityRepository, never()).observeActiveSims()

            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
            val emptySimsFlow = flowOf(emptyList<ActiveSimInfo>())
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(emptySimsFlow)
            tracker.updateTrafficUsage()
            testScheduler.runCurrent()
            @Suppress("UnusedFlow")
            verify(simIdentityRepository, times(1)).observeActiveSims()
        }

    @Test
    fun `updateTrafficUsage does not skip delta after identity timeout`() =
        runTest(testDispatcher) {
            // 1. Successful first run to set initial state
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            val lastTotalFlow = flowOf(1000L)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(lastTotalFlow)

            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)

            // 2. Second run: observeActiveSims times out
            whenever(simIdentityRepository.observeActiveSims())
                .thenReturn(MutableSharedFlow()) // Never emits
            tracker.updateTrafficUsage()

            assertTrue(tracker.status.value is TrackingStatus.Error)
            assertEquals(
                "SIM list read timeout",
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
            verify(simUsageResetRepository).incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(50000L))
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `getActiveSimsAndCleanup timeout does not reset inMemoryLastDataSubId`() =
        runTest(testDispatcher) {
            // 1. Initial success to set inMemoryLastDataSubId to 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            val simChangeHandlerField = TrafficTracker::class.java.getDeclaredField("simChangeHandler")
            simChangeHandlerField.isAccessible = true
            val simChangeHandler = simChangeHandlerField[tracker] as TrafficSimChangeHandler

            val field = TrafficSimChangeHandler::class.java.getDeclaredField("inMemoryLastDataSubId")
            field.isAccessible = true
            val subId = field[simChangeHandler] as Int
            assertEquals(1, subId)

            // 2. Timeout on observeActiveSims
            whenever(simIdentityRepository.observeActiveSims())
                .thenReturn(MutableSharedFlow())
            tracker.updateTrafficUsage()

            // 3. Verify inMemoryLastDataSubId is STILL 1, not -1
            val subIdAfter = field[simChangeHandler] as Int
            assertEquals("inMemoryLastDataSubId should NOT be reset on timeout", 1, subIdAfter)
        }

    @Test
    fun `updateTrafficUsage reads fresh lastTotalBytes after resetBaseline due to serialization`() =
        runTest(testDispatcher) {
            val currentStoredValue = MutableStateFlow(1000L)

            // Mock repository to reflect updates in-memory
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(currentStoredValue)
            whenever(simUsageResetRepository.setLastTotalBytes(eq("sim_0"), any())).thenAnswer {
                currentStoredValue.value = it.getArgument(1)
            }
            whenever(simUsageResetRepository.incrementTrafficAndSetBaseline(eq("sim_0"), any(), any())).thenAnswer {
                currentStoredValue.value = it.getArgument(2)
            }

            // Mock network stats: first call sets 5000, second call adds 100000 (total 105000)
            var queryCount = 0
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenAnswer {
                queryCount++
                StatsResult.Success(if (queryCount == 1) 5000L else 105000L)
            }

            // Launch both.
            val job1 = launch { tracker.resetBaseline("sim_0") }
            val job2 = launch { tracker.updateTrafficUsage() }

            // Allow them to proceed. simChangeMutex ensures they don't race.
            testScheduler.runCurrent()

            // Verify delta was calculated from 5000, not the initial 1000
            val expectedDeltaMb = (100000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageResetRepository)
                .incrementTrafficAndSetBaseline(eq("sim_0"), eq(expectedDeltaMb), eq(105000L))
            assertEquals(105000L, currentStoredValue.value)

            job1.join()
            job2.join()
        }

    @Test
    fun `ensureInMemoryLastDataSubId avoids redundant DataStore reads`() =
        runTest(testDispatcher) {
            // Setup: initial state with sim_0
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            val lastTotalFlow2 = flowOf(1000L)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(lastTotalFlow2)

            // Mock DataStore read for lastDataSubId
            val lastDataSubIdFlow = flowOf(1)
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(lastDataSubIdFlow)

            // First call: should read from DataStore
            tracker.updateTrafficUsage()
            @Suppress("UnusedFlow")
            verify(callSessionRepository, times(1)).getLastDataSubId()

            // Second call: should NOT read from DataStore again
            tracker.updateTrafficUsage()
            @Suppress("UnusedFlow")
            verify(callSessionRepository, times(1)).getLastDataSubId()
        }

    @Test
    fun `updateTrafficUsage sets error status on identity timeout`() =
        runTest(testDispatcher) {
            // Arrange: observeActiveSims never emits
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())

            // Act
            tracker.updateTrafficUsage()
            testScheduler.advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS * 3) // Advance through initial and retry
            testScheduler.runCurrent()

            // Assert
            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals("SIM list read timeout", (status as TrackingStatus.Error).message)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, status.type)
        }

    @Test
    fun `getActiveSimsAndCleanup timeout does not clear baselineNeedsRetry`() =
        runTest(testDispatcher) {
            // Arrange: add an entry to baselineNeedsRetry
            val identity = "sim_0"
            val baselineManagerField = TrafficTracker::class.java.getDeclaredField("baselineManager")
            baselineManagerField.isAccessible = true
            val baselineManager = baselineManagerField[tracker] as TrafficBaselineManager

            val field = TrafficBaselineManager::class.java.getDeclaredField("baselineNeedsRetry")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field[baselineManager] as MutableMap<String, Int>
            map[identity] = 1

            // Mock timeout
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())

            // Act
            tracker.updateTrafficUsage()
            testScheduler.advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS * 3)
            testScheduler.runCurrent()

            // Assert
            assertTrue("baselineNeedsRetry should NOT be cleared on timeout", map.containsKey(identity))
        }

    @Test
    fun `resetBaseline initializes inMemoryLastDataSubId and avoids subsequent DataStore read`() =
        runTest(testDispatcher) {
            // Setup: sim_0 is active default data SIM (subId 1)
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            val lastTotalFlow6 = flowOf(1000L)
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(lastTotalFlow6)

            // resetBaseline will call updateLastDataSubId(1) which sets inMemoryLastDataSubId
            tracker.resetBaseline("sim_0")

            // updateTrafficUsage should now see it's initialized and NOT read from DataStore
            tracker.updateTrafficUsage()

            @Suppress("UnusedFlow")
            verify(callSessionRepository, never()).getLastDataSubId()
        }

    @Test
    fun `baselineNeedsRetry is cleared of inactive SIMs after successful call following timeout`() =
        runTest(testDispatcher) {
            val staleIdentity = "stale_sim"
            val baselineManagerField = TrafficTracker::class.java.getDeclaredField("baselineManager")
            baselineManagerField.isAccessible = true
            val baselineManager = baselineManagerField[tracker] as TrafficBaselineManager

            val field = TrafficBaselineManager::class.java.getDeclaredField("baselineNeedsRetry")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field[baselineManager] as MutableMap<String, Int>
            map[staleIdentity] = 1

            // 1. First call: timeout on observeActiveSims
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())
            tracker.updateTrafficUsage()
            assertTrue("Should still contain stale_sim after timeout", map.containsKey(staleIdentity))

            // 2. Second call: success, but stale_sim is NOT in activeSims
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
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            val lastTotalFlow3 = flowOf(1000L)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(lastTotalFlow3)

            tracker.updateTrafficUsage()

            // 3. Verify stale_sim is gone
            assertFalse("stale_sim should be cleared after successful call", map.containsKey(staleIdentity))
            assertTrue("sim_0 should not be in retry map unless it failed", map.isEmpty())
        }

    @Test
    fun `updateLastDataSubId updates memory even if repository fails`() =
        runTest(testDispatcher) {
            // 1. Initial state: subId = 1
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            val lastTotalFlow4 = flowOf(1000L)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(lastTotalFlow4)
            tracker.updateTrafficUsage()
            verify(callSessionRepository).setLastDataSubId(1)

            // 2. SIM swap to subId = 2, but repository fails
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(callSessionRepository.setLastDataSubId(2)).thenThrow(RuntimeException("Persistence failed"))

            tracker.updateTrafficUsage()

            // Verify it was called once (and failed)
            verify(callSessionRepository).setLastDataSubId(2)

            // Reset inMemoryLastDataSubId to simulate clean state if needed?
            // No, the whole point is it stays updated.

            // 3. Third call: same subId = 2
            // It should NOT call handleSimChange or updateLastDataSubId again
            // because inMemoryLastDataSubId is already 2
            tracker.updateTrafficUsage()

            verify(callSessionRepository, times(1)).setLastDataSubId(2)
        }

    @Test
    fun `updateTrafficUsage does not block concurrent calls during IO`() =
        runTest(testDispatcher) {
            val flow = MutableSharedFlow<List<ActiveSimInfo>>()
            val callCount =
                java.util.concurrent.atomic
                    .AtomicInteger(0)

            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                callCount.incrementAndGet()
                flow
            }

            // Start two calls
            val job1 = launch { tracker.updateTrafficUsage() }
            val job2 = launch { tracker.updateTrafficUsage() }

            // Allow them to reach fetchActiveSims() -> observeActiveSims().first()
            testScheduler.runCurrent()

            // If not blocked by trafficMutex, both should have called observeActiveSims()
            assertEquals(2, callCount.get())

            // Clean up by emitting empty list to finish the calls
            flow.emit(emptyList())
            testScheduler.runCurrent()

            job1.join()
            job2.join()
        }
}

private fun createSimUsage(id: String) =
    SimUsage(
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
        lastResetDate = 0L,
        rolloverEnabled = false,
    )
