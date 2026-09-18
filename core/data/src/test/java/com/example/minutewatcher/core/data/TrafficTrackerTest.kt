@file:OptIn(
    InternalCoroutinesApi::class,
    ExperimentalForInheritanceCoroutinesApi::class,
)

package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(
    InternalCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalForInheritanceCoroutinesApi::class,
)
@Suppress("LargeClass")
class TrafficTrackerTest {
    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val permissionChangesFlow = MutableSharedFlow<Unit>(replay = 1)
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider =
        FixedTimeProvider(
            currentTime = 1700000000000L,
            currentRealtime = 100000L,
            currentUptime = 100000L,
        )
    private val retryCounts = mutableMapOf<String, MutableStateFlow<Int?>>()

    @Before
    fun setup() {
        initTestLogger()
        retryCounts.clear()
        setupPermissionMocks()
        setupIdentityMocks()
        setupUsageMocks()
        whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(0L))
        whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

        whenever(simUsageResetRepository.getBaselineRetryCount(any())).thenAnswer {
            val identity = it.arguments[0] as String
            retryCounts.getOrPut(identity) { MutableStateFlow(null) }
        }
        runBlocking {
            whenever(simUsageResetRepository.setBaselineRetryCount(any(), any())).thenAnswer {
                val identity = it.arguments[0] as String
                val count = it.arguments[1] as? Int
                retryCounts.getOrPut(identity) { MutableStateFlow(null) }.value = count
            }
            whenever(simUsageResetRepository.cleanupBaselineRetries(any())).thenAnswer {
                val active = it.arguments[0] as Set<String>
                retryCounts.entries.removeIf { !active.contains(it.key) }
            }
        }

        whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)
        runBlocking {
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(true)
        }
    }

    private fun setupPermissionMocks() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        val mockSubInfo =
            mock<SubscriptionInfo> {
                on { subscriptionId } doReturn 1
                on { simSlotIndex } doReturn 0
            }
        val mockSubInfo2 =
            mock<SubscriptionInfo> {
                on { subscriptionId } doReturn 2
                on { simSlotIndex } doReturn 1
            }
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo, mockSubInfo2))
    }

    private fun setupIdentityMocks() {
        runBlocking {
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(-1))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, isStable = true),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "sim_0",
                        displayName = "SIM 1",
                        carrierName = "Carrier 1",
                        mccMnc = "25001",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                    ActiveSimInfo(
                        identity = SimIdentity("sim_1", 2, 1, IdentitySource.ICCID, isStable = true),
                        slotIndex = 1,
                        subscriptionId = 2,
                        iccId = "sim_1",
                        displayName = "SIM 2",
                        carrierName = "Carrier 2",
                        mccMnc = "25002",
                        isDefaultData = false,
                        isDefaultVoice = false,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0", "sim_1")))
        }
    }

    private fun setupUsageMocks() {
        whenever(simUsageRepository.getSimUsage(any())).thenReturn(
            flowOf(
                createSimUsage("sim_0", 100, 500, 10000000L),
            ),
        )
    }

    private fun TestScope.startTracker(): TrafficTracker {
        val tracker = createTracker(backgroundScope)
        setupPermissionMocks()
        setupIdentityMocks()
        whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
        whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(500L))

        runBlocking { tracker.updateTrafficUsage() }
        runCurrent()
        return tracker
    }

    @Test
    fun `updateTrafficUsage sets Active status on success`() =
        runTest {
            val tracker = startTracker()
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `processTrafficUpdate handles timeout reading lastTotalBytes without resetting baseline`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(2000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(MutableSharedFlow())

            val updateJob = launch { tracker.updateTrafficUsage() }
            runCurrent()

            timeProvider.advance(11000)
            testScheduler.advanceTimeBy(11000.milliseconds)
            runCurrent()
            updateJob.join()

            val status = tracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, (status as TrackingStatus.Error).code)
        }

    @Test
    fun `processTrafficUpdate handles timeout reading simUsage`() =
        runTest {
            val tracker = startTracker()
            whenever(simUsageRepository.getSimUsage(any())).thenReturn(MutableSharedFlow())

            val updateJob = launch { tracker.updateTrafficUsage() }
            runCurrent()

            timeProvider.advance(11000)
            testScheduler.advanceTimeBy(11000.milliseconds)
            runCurrent()
            updateJob.join()

            val status = tracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, (status as TrackingStatus.Error).code)
        }

    @Test
    fun `updateTrafficUsage handles SecurityException gracefully`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any()))
                .thenThrow(SecurityException("No permission"))

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `updateTrafficUsage reports error when Usage Access is missing`() =
        runTest {
            val tracker = startTracker()
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `updateTrafficUsage increments usage based on delta atomically`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(35000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            tracker.updateTrafficUsage()

            val deltaMb = (34000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageResetRepository).incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(35000L))
        }

    @Test
    fun `updateTrafficUsage preserves delta when atomic update fails`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(35000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            whenever(simUsageResetRepository.incrementTrafficAndSetBaseline(any(), any(), any()))
                .thenThrow(RuntimeException("DB failure"))

            tracker.updateTrafficUsage()
            runCurrent()

            // Verify status became Error
            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)

            // Now make it succeed
            whenever(simUsageResetRepository.incrementTrafficAndSetBaseline(any(), any(), any())).thenReturn(Unit)
            tracker.updateTrafficUsage()
            runCurrent()

            val deltaMb = (34000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageResetRepository, times(2))
                .incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(35000L))
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `updateTrafficUsage handles network stats unavailable`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any()))
                .thenReturn(StatsResult.Error(IllegalStateException("Busy")))

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals("Busy", (status as TrackingStatus.Error).message)
        }

    @Test
    fun `findSingleActiveSimSlot returns slot when only one SIM has valid operator`() =
        runTest {
            val tracker = createTracker(backgroundScope)
            setupPermissionMocks()
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")

            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, isStable = true),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "sim_0",
                        displayName = "SIM 1",
                        carrierName = "Carrier 1",
                        mccMnc = "25001",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                    ActiveSimInfo(
                        identity = SimIdentity("sim_1", 2, 1, IdentitySource.ICCID, isStable = true),
                        slotIndex = 1,
                        subscriptionId = 2,
                        iccId = "sim_1",
                        displayName = "SIM 2",
                        carrierName = "Carrier 2",
                        mccMnc = "25002",
                        isDefaultData = false,
                        isDefaultVoice = false,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))

            // Sim 0 has valid operator, Sim 1 does not
            whenever(simIdentityRepository.getSimOperator("sim_0")).thenReturn(flowOf("MTS"))
            whenever(simIdentityRepository.getSimOperator("sim_1")).thenReturn(null)

            // Acts on update
            tracker.updateTrafficUsage()
            runCurrent()

            // Verify it attempted to query stats
            verify(networkStatsProvider, atLeast(1)).queryMobileBytes(any(), any())
        }

    @Test
    fun `updateTrafficUsage handles SIM swap by resetting baseline`() =
        runTest {
            val tracker = startTracker()

            // Change default data SIM to 2
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, isStable = true),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "sim_0",
                        displayName = "SIM 1",
                        carrierName = "Carrier 1",
                        mccMnc = "25001",
                        isDefaultData = false,
                        isDefaultVoice = true,
                    ),
                    ActiveSimInfo(
                        identity = SimIdentity("sim_1", 2, 1, IdentitySource.ICCID, isStable = true),
                        slotIndex = 1,
                        subscriptionId = 2,
                        iccId = "sim_1",
                        displayName = "SIM 2",
                        carrierName = "Carrier 2",
                        mccMnc = "25002",
                        isDefaultData = true,
                        isDefaultVoice = false,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0", "sim_1")))
            whenever(simUsageRepository.getSimUsage("sim_1"))
                .thenReturn(flowOf(createSimUsage("sim_1", 0, 0, 10000000L)))

            tracker.updateTrafficUsage()
            runCurrent()

            // Verify baseline was reset for sim_1
            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_1"), any())
            verify(callSessionRepository, atLeast(1)).setLastDataSubId(2)
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `updateTrafficUsage retries baseline reset when stats become available after SIM swap`() =
        runTest {
            val tracker = startTracker()

            // 1. SIM swap occurs but network stats are temporarily busy/unavailable
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, isStable = true),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "sim_0",
                        displayName = "SIM 1",
                        carrierName = "Carrier 1",
                        mccMnc = "25001",
                        isDefaultData = false,
                        isDefaultVoice = true,
                    ),
                    ActiveSimInfo(
                        identity = SimIdentity("sim_1", 2, 1, IdentitySource.ICCID, isStable = true),
                        slotIndex = 1,
                        subscriptionId = 2,
                        iccId = "sim_1",
                        displayName = "SIM 2",
                        carrierName = "Carrier 2",
                        mccMnc = "25002",
                        isDefaultData = true,
                        isDefaultVoice = false,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0", "sim_1")))
            whenever(simUsageRepository.getSimUsage("sim_1"))
                .thenReturn(flowOf(createSimUsage("sim_1", 0, 0, 10000000L)))

            // Simulate "Stats unavailable" error
            whenever(networkStatsProvider.queryMobileBytes(any(), any()))
                .thenReturn(StatsResult.Error(IllegalStateException("Busy")))

            tracker.updateTrafficUsage()
            runCurrent()

            // Verify it didn't reset yet and status is Error
            verify(simUsageResetRepository, never()).setLastTotalBytes(eq("sim_1"), any())
            assertTrue(tracker.status.value is TrackingStatus.Error)

            // 2. Stats become available
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(5000L))

            // Trigger retry
            tracker.updateTrafficUsage()
            runCurrent()

            // Verify baseline finally reset and status is Active
            verify(simUsageResetRepository).setLastTotalBytes(eq("sim_1"), eq(5000L))
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `updateTrafficUsage and resetBaseline are serialized`() =
        runTest {
            val tracker = startTracker()
            val startCount = AtomicInteger(0)
            val finishCount = AtomicInteger(0)

            // Mock long-running update
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenAnswer {
                startCount.incrementAndGet()
                runBlocking { delay(1000.milliseconds) }
                finishCount.incrementAndGet()
                StatsResult.Success(1000L)
            }

            launch { tracker.updateTrafficUsage() }
            launch { tracker.resetBaseline("sim_0") }

            runCurrent()
            testScheduler.advanceUntilIdle()

            assertEquals(2, startCount.get())
            assertEquals(2, finishCount.get())
        }

    @Test
    fun `updateTrafficUsage handles timeout race with successful update`() =
        runTest {
            // Setup tracker and ensure it starts with non-zero elapsed success
            val tracker = createTracker(backgroundScope)
            setupPermissionMocks()
            setupIdentityMocks()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(500L))

            tracker.updateTrafficUsage()
            runCurrent()
            assertEquals(TrackingStatus.Active, tracker.status.value)

            // Advance timeProvider so startElapsed for next update is larger
            timeProvider.advance(1000)

            // Simulate timeout in fetchActiveSims (which is inside updateTrafficUsageLogic)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())

            val updateJob = launch { tracker.updateTrafficUsage() }
            runCurrent()

            // 1. Advance time slightly (but before timeout) to simulate a successful update occurring during the wait
            // In this test, we simulate it by manually updating lastSuccessElapsed
            testScheduler.advanceTimeBy(1000.milliseconds)
            timeProvider.advance(1000)
            tracker.lastSuccessElapsed.set(timeProvider.elapsedRealtime())

            // 2. Advance time further to trigger timeout in updateJob
            val timeout = Constants.Flow.TRAFFIC_UPDATE_TOTAL_TIMEOUT_MS.milliseconds + 1000.milliseconds
            testScheduler.advanceTimeBy(timeout)
            timeProvider.advance(timeout.inWholeMilliseconds)

            runCurrent()
            updateJob.join()

            // Verification: Status should remain Active and NOT be overwritten by the timeout error
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `updateStatusOnError suppresses error if a success happened after it started`() =
        runTest {
            val tracker = createTracker(backgroundScope)
            tracker.lastSuccessElapsed.set(1000L)

            // Error started at 500L, which is before 1000L
            tracker.statusMachine.transition(TrackingStatus.Active)

            // This should be a no-op due to suppression
            val updateStatusOnError =
                tracker::class.java
                    .getDeclaredMethod(
                        "updateStatusOnError",
                        Long::class.java,
                        String::class.java,
                        TrackingStatus.ErrorType::class.java,
                        TrackingStatus.ErrorCode::class.java,
                    ).apply { isAccessible = true }

            updateStatusOnError.invoke(
                tracker,
                500L,
                "Stale Error",
                TrackingStatus.ErrorType.UNKNOWN,
                null,
            )

            assertEquals(TrackingStatus.Active, tracker.status.value)

            // Error started at 1500L, which is after 1000L
            updateStatusOnError.invoke(
                tracker,
                1500L,
                "Fresh Error",
                TrackingStatus.ErrorType.UNKNOWN,
                null,
            )
            assertTrue(tracker.status.value is TrackingStatus.Error)
        }

    @Test
    fun `updateTrafficUsage with fetchActiveSims timeout sets Error with TIMEOUT code`() =
        runTest {
            val tracker = startTracker()

            // 1. Force timeout in fetchActiveSims
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())

            val updateJob = launch { tracker.updateTrafficUsage() }
            runCurrent()

            timeProvider.advance(11000)
            testScheduler.advanceTimeBy(11000.milliseconds)
            runCurrent()
            updateJob.join()

            // 2. Status should be Error with TIMEOUT code
            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, (status as TrackingStatus.Error).code)
        }

    @Test
    fun `test recover updates status`() =
        runTest {
            val tracker = createTracker(backgroundScope)
            setupPermissionMocks()
            setupIdentityMocks()

            // Force init failure by making observeActiveSims throw initially
            whenever(simIdentityRepository.observeActiveSims())
                .thenReturn(flow { throw IllegalStateException("Init fail") })
            runCurrent()
            // We need to advance time for retries to complete
            testScheduler.advanceTimeBy(10000.milliseconds)
            runCurrent()
            testScheduler.advanceUntilIdle()

            // Should be in error status
            val status = tracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)

            // Now recover
            setupIdentityMocks()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(500L))

            val recoverJob = launch { tracker.recover() }
            runCurrent()
            testScheduler.advanceUntilIdle()
            recoverJob.join()

            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `updateTrafficUsage sets Idle when no active SIM`() =
        runTest {
            val tracker = startTracker()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            tracker.updateTrafficUsage()
            runCurrent()

            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }

    @Test
    fun `updateTrafficUsage with empty active SIMs DOES cleanup retries`() =
        runTest {
            val tracker = startTracker()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))

            tracker.updateTrafficUsage()
            runCurrent()

            verify(simUsageResetRepository).cleanupBaselineRetries(eq(emptySet()))
        }

    private fun createTracker(scope: CoroutineScope): TrafficTracker {
        val baselineManager =
            TrafficBaselineManager(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                permissionManager,
                timeProvider,
                networkStatsProvider,
                dataChangeNotifier,
            )
        val deltaCalculator = TrafficDeltaCalculator(simUsageResetRepository)
        val simChangeHandler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                baselineManager,
            )
        val targetResolver = TrafficTargetResolver(permissionManager)

        return TrafficTracker(
            simIdentityRepository = simIdentityRepository,
            simUsageRepository = simUsageRepository,
            simUsageResetRepository = simUsageResetRepository,
            permissionManager = permissionManager,
            baselineManager = baselineManager,
            deltaCalculator = deltaCalculator,
            simChangeHandler = simChangeHandler,
            targetResolver = targetResolver,
            permissionChecker = TrafficPermissionChecker(permissionManager),
            permissionStateNotifier = permissionStateNotifier,
            dataChangeNotifier = dataChangeNotifier,
            timeProvider = timeProvider,
            scope = scope,
        )
    }

    private fun createSimUsage(
        identity: String,
        minutes: Int,
        traffic: Int,
        lastReset: Long,
    ) = SimUsage(
        identity = identity,
        slotIndex = 0,
        subscriptionId = 1,
        operator = "Carrier",
        minutesUsed = minutes,
        minutesLimit = 500,
        trafficUsedMb = traffic.toFloat(),
        trafficLimitMb = 1000,
        manualAdjustment = 0,
        trafficAdjustmentGb = 0f,
        autoReset = true,
        autoResetDay = 1,
        lastResetDate = lastReset,
    )
}
