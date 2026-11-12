package host.serv.djbigmuh.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val networkStatsProvider: NetworkStatsProvider = mock()

    private val permissionChangesFlow = MutableSharedFlow<Unit>(replay = 1)
    private val timeProvider =
        FixedTimeProvider(
            currentTime = 1700000000000L,
            currentRealtime = 100000L,
            currentUptime = 100000L,
        )
    private val retryCounts = mutableMapOf<String, MutableStateFlow<Int?>>()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    @Before
    fun setup() =
        runTest(testDispatcher) {
            initTestLogger()
            retryCounts.clear()
            setupPermissionMocks()
            setupIdentityMocks()
            setupUsageMocks()
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(0L))
            whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(-1L))
            whenever(simUsageResetRepository.ensureTrackingWindowInitialized(any())).thenReturn(true)
            whenever(simUsageResetRepository.setLastTotalBytes(any(), any(), any())).thenReturn(true)
            whenever(
                simUsageResetRepository.incrementTrafficAndSetBaseline(
                    any(),
                    any(),
                    any(),
                    any(),
                ),
            ).thenReturn(true)

            whenever(simUsageResetRepository.getBaselineRetryCount(any())).thenAnswer {
                val identity = it.arguments[0] as String
                retryCounts.getOrPut(identity) { MutableStateFlow(null) }
            }
            whenever(simUsageResetRepository.setBaselineRetryCount(any(), any())).thenAnswer {
                val identity = it.arguments[0] as String
                val count = it.arguments[1] as? Int
                retryCounts.getOrPut(identity) { MutableStateFlow(null) }.value = count
            }
            whenever(simUsageResetRepository.cleanupBaselineRetries(any())).thenAnswer {
                val active = it.arguments[0] as Set<String>
                retryCounts.entries.removeIf { !active.contains(it.key) }
            }

            whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(true)
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

    private fun activeSimsFlow(sims: List<ActiveSimInfo> = emptyList()): MutableSharedFlow<List<ActiveSimInfo>> =
        MutableSharedFlow<List<ActiveSimInfo>>(replay = 1).apply {
            tryEmit(sims)
        }

    private suspend fun setupIdentityMocks() {
        whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(-1))
        whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
        whenever(simIdentityRepository.getIdentity(1)).thenReturn("sim_1")
        val activeSims =
            listOf(
                ActiveSimInfo(
                    identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = "icc_0",
                    displayName = "SIM 0",
                    carrierName = "C0",
                    isDefaultData = true,
                    isDefaultVoice = true,
                ),
            )
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(activeSimsFlow(activeSims))
        whenever(simIdentityRepository.observeResolvedActiveSims()).thenReturn(MutableStateFlow(activeSims))
        whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0")))
    }

    private suspend fun setupUsageMocks() {
        whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(
            flowOf(
                SimUsage(
                    identity = "sim_0",
                    slotIndex = 0,
                    subscriptionId = 1,
                    operator = "Op",
                    minutesUsed = 0,
                    minutesLimit = 0,
                    manualAdjustment = 0,
                    trafficUsedMb = 100f,
                    trafficLimitMb = 1000,
                    trafficAdjustmentGb = 0f,
                    autoReset = true,
                    autoResetDay = 1,
                    lastResetDate = 1700000000000L,
                    rolloverEnabled = false,
                ),
            ),
        )
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
                DataStoreRetryHelper(),
            )
        val deltaCalculator = TrafficDeltaCalculator(simUsageResetRepository)
        val simChangeHandler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                baselineManager,
            )
        val deps =
            TrafficTrackerDependencies(
                simIdentityRepository = simIdentityRepository,
                simUsageRepository = simUsageRepository,
                simUsageResetRepository = simUsageResetRepository,
                permissionManager = permissionManager,
                baselineManager = baselineManager,
                deltaCalculator = deltaCalculator,
                simChangeHandler = simChangeHandler,
                targetResolver = TrafficTargetResolver(permissionManager),
                permissionChecker = TrafficPermissionChecker(permissionManager),
                permissionStateNotifier = permissionStateNotifier,
                dataChangeNotifier = dataChangeNotifier,
                timeProvider = timeProvider,
            )
        return TrafficTracker(deps, scope)
    }

    private suspend fun TestScope.startTracker(): TrafficTracker {
        setupPermissionMocks()
        setupIdentityMocks()
        val tracker = createTracker(backgroundScope)
        whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(1000L))
        whenever(simUsageResetRepository.getLastTotalBytes(any())).thenReturn(flowOf(500L))

        tracker.updateTrafficUsage()
        runCurrent()
        return tracker
    }

    @Test
    fun `performTrafficUpdate handles timeout reading simUsage`() =
        runTest {
            val tracker = startTracker()
            whenever(simUsageRepository.getSimUsage(any())).thenReturn(MutableSharedFlow())

            val updateJob = launch { tracker.updateTrafficUsage() }
            runCurrent()

            timeProvider.advance(11000)
            advanceTimeBy(11000.milliseconds)
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
            runCurrent()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
        }

    @Test
    fun `updateTrafficUsage handles IOException gracefully`() =
        runTest {
            val tracker = startTracker()
            whenever(networkStatsProvider.queryMobileBytes(any(), any()))
                .thenThrow(RuntimeException("Network error"))

            tracker.updateTrafficUsage()
            runCurrent()

            val status = tracker.status.value
            assertTrue(status is TrackingStatus.Error)
        }
}
