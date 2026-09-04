package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
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
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
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
    private val timeProvider = FixedTimeProvider(currentTime = 1700000000000L)

    @Before
    fun setup() {
        initTestLogger()
        setupPermissionMocks()
        setupIdentityMocks()
        setupUsageMocks()
        whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(0L))
        whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(0L))
        whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)
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
            whenever(simIdentityRepository.getSimOperator(any())).thenReturn(flowOf("Test Operator"))
        }
    }

    private fun TestScope.startTracker(): TrafficTracker {
        val t = createTracker(backgroundScope)
        testScheduler.advanceUntilIdle()
        clearInvocations(
            simIdentityRepository,
            simUsageRepository,
            simUsageResetRepository,
            permissionManager,
            networkStatsProvider,
            callSessionRepository,
        )
        return t
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
        runTest {
            val tracker = startTracker()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(emptyList())

            tracker.updateTrafficUsage()

            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }

    @Test
    fun `updateTrafficUsage initializes baseline on first run`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_0"), eq(1500L))
            verify(callSessionRepository, atLeast(1)).setLastDataSubId(1)
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `processTrafficUpdate handles timeout reading lastTotalBytes without resetting baseline`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(2000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(MutableSharedFlow())

            tracker.updateTrafficUsage()

            testScheduler.advanceTimeBy(10001.milliseconds)
            runCurrent()

            val status = tracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            val message = (status as TrackingStatus.Error).message
            assertTrue(
                "Unexpected message: $message",
                message == "Timeout reading lastTotalBytes" || message == "Traffic update timeout",
            )
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
        }

    @Test
    fun `processTrafficUpdate handles timeout reading simUsage`() =
        runTest {
            val tracker = startTracker()
            whenever(simUsageRepository.getSimUsage(any())).thenReturn(MutableSharedFlow())

            tracker.updateTrafficUsage()

            testScheduler.advanceTimeBy(10001.milliseconds)
            runCurrent()

            val status = tracker.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            val message = (status as TrackingStatus.Error).message
            assertTrue(
                "Unexpected message: $message",
                message == "Timeout reading simUsage" || message == "Traffic update timeout",
            )
            verify(simUsageResetRepository, never()).setLastTotalBytes(any(), any())
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
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(35000L))
            whenever(simUsageResetRepository.incrementTrafficAndSetBaseline(any(), any(), any()))
                .thenThrow(RuntimeException("Write failed"))

            tracker.updateTrafficUsage()

            val deltaMb = (34000L.toDouble() / Constants.BYTES_PER_MB).toFloat()
            verify(simUsageResetRepository).incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(35000L))

            whenever(simUsageResetRepository.incrementTrafficAndSetBaseline(any(), any(), any()))
                .thenReturn(Unit)

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository, times(2))
                .incrementTrafficAndSetBaseline(eq("sim_0"), eq(deltaMb), eq(35000L))
        }

    @Test
    fun `updateTrafficUsage handles network stats unavailable`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Unavailable)

            tracker.updateTrafficUsage()

            assertEquals(
                TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Stats unavailable"),
                tracker.status.value,
            )
        }

    @Test
    fun `findSingleActiveSimSlot returns slot when only one SIM has valid operator`() =
        runTest {
            val tracker = startTracker()
            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(-1)
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.getSimOperator("sim_0")).thenReturn(flowOf("MTS"))
            whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")
            whenever(simIdentityRepository.getSimOperator("sim_1")).thenReturn(flowOf(""))

            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_0"), eq(1500L))
        }

    @Test
    fun `updateTrafficUsage handles SIM swap by resetting baseline`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.updateTrafficUsage()
            verify(callSessionRepository, atLeast(1)).setLastDataSubId(1)
            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_0"), eq(1000L))

            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))

            tracker.updateTrafficUsage()

            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_1"), eq(1500L))
            verify(callSessionRepository, atLeast(1)).setLastDataSubId(2)
        }

    @Test
    fun `updateTrafficUsage retries baseline reset when stats become available after SIM swap`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))
            tracker.updateTrafficUsage()

            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)

            var callCount = 0
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenAnswer {
                callCount++
                if (callCount == 1) StatsResult.Unavailable else StatsResult.Success(2000L)
            }

            tracker.updateTrafficUsage()
            verify(simUsageResetRepository, never()).setLastTotalBytes(eq("sim_1"), any())
            assertTrue(tracker.status.value is TrackingStatus.Error)

            tracker.updateTrafficUsage()
            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_1"), eq(2000L))
        }

    @Test
    fun `resetBaseline initializes storage correctly`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))

            tracker.resetBaseline("sim_0")

            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_0"), eq(1500L))
            assertEquals(TrackingStatus.Active, tracker.status.value)
        }

    @Test
    fun `test recover updates status`() =
        runTest {
            val tracker = startTracker()
            clearInvocations(simUsageResetRepository)

            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1500L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))

            tracker.recover()
            runCurrent()

            assertEquals(TrackingStatus.Active, tracker.status.value)
            verify(simUsageResetRepository, atLeast(1)).setLastTotalBytes(eq("sim_0"), eq(1500L))
        }

    @Test
    fun `updateTrafficUsage and resetBaseline are serialized`() =
        runTest {
            val tracker = startTracker()

            val flow = MutableSharedFlow<Long>(replay = 1)
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flow)

            val job1 = launch { tracker.updateTrafficUsage() }
            runCurrent()
            testScheduler.advanceUntilIdle()

            val job2 = launch { tracker.resetBaseline("sim_0") }
            runCurrent()
            testScheduler.advanceUntilIdle()

            // After job1 starts, job2 is blocked.
            verify(networkStatsProvider, atLeast(1)).queryMobileBytes(any(), any())

            flow.emit(1000L)
            runCurrent()
            testScheduler.advanceUntilIdle()
            job1.join()
            job2.join()

            verify(networkStatsProvider, atLeast(2)).queryMobileBytes(any(), any())
        }

    @Test
    fun `ensureInMemoryLastDataSubId avoids redundant DataStore reads`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            // Population
            tracker.updateTrafficUsage()
            runCurrent()

            clearInvocations(callSessionRepository)

            tracker.updateTrafficUsage()
            runCurrent()

            @Suppress("UnusedFlow")
            verify(callSessionRepository, never()).getLastDataSubId()
        }

    @Test
    fun `updateTrafficUsage does not block concurrent calls during IO`() =
        runTest {
            val tracker = startTracker()
            val flow = MutableSharedFlow<List<ActiveSimInfo>>()
            val callCount = AtomicInteger(0)

            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                callCount.incrementAndGet()
                flow
            }

            val job1 = launch { tracker.updateTrafficUsage() }
            val job2 = launch { tracker.updateTrafficUsage() }
            runCurrent()

            // 1 from SIM observer, 1 from init, 2 from current calls
            assertEquals(4, callCount.get())

            flow.emit(emptyList())
            runCurrent()
            job1.join()
            job2.join()
        }

    @Test
    fun `TrafficTracker automatically recovers on permission change after initialization failure`() =
        runTest {
            val shouldThrow = AtomicBoolean(true)
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                if (shouldThrow.get()) throw SecurityException("Fail")
                flowOf(
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
                    ),
                )
            }

            val localTracker = createTracker(backgroundScope)
            testScheduler.advanceUntilIdle()

            shouldThrow.set(false)
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

            permissionChangesFlow.emit(Unit)
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()

            assertEquals(TrackingStatus.Active, localTracker.status.value)
        }

    @Test
    fun `TrafficTracker automatically recovers on SIM change after initialization failure`() =
        runTest {
            val shouldThrow = AtomicBoolean(true)
            val simFlow = MutableSharedFlow<List<ActiveSimInfo>>(replay = 1)

            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                if (shouldThrow.get()) throw SecurityException("Fail")
                simFlow
            }

            val localTracker = createTracker(backgroundScope)
            testScheduler.advanceUntilIdle()

            shouldThrow.set(false)
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(1000L))

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
                )
            simFlow.emit(activeSims)
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()

            assertEquals(TrackingStatus.Active, localTracker.status.value)
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

        return TrafficTracker(
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
            permissionStateNotifier = permissionStateNotifier,
            scope = scope,
        )
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
