package host.serv.djbigmuh.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerOutcomesTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val permissionChangesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider =
        FixedTimeProvider(
            currentTime = 1700000000000L,
            currentRealtime = 100000L,
            currentUptime = 100000L,
        )

    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()

    private fun createTracker(scope: TestScope): TrafficTracker {
        val deps =
            TrafficTrackerDependencies(
                simIdentityRepository = simIdentityRepository,
                simUsageRepository = simUsageRepository,
                simUsageResetRepository = simUsageResetRepository,
                permissionManager = permissionManager,
                baselineManager = baselineManager,
                deltaCalculator = deltaCalculator,
                simChangeHandler = simChangeHandler,
                targetResolver = targetResolver,
                permissionChecker = permissionChecker,
                permissionStateNotifier = permissionStateNotifier,
                dataChangeNotifier = dataChangeNotifier,
                timeProvider = timeProvider,
            )
        return TrafficTracker(deps, scope)
    }

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionStateNotifier.changes).thenReturn(permissionChangesFlow)
        whenever(permissionChecker.checkPermissions(any())).thenAnswer { true }
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)

        val mockSubInfo =
            mock<SubscriptionInfo> {
                on { subscriptionId } doReturn 1
                on { simSlotIndex } doReturn 0
            }
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo))

        val mockUsage = mock<SimUsage> { on { lastResetDate } doReturn 1000L }

        runBlocking {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(activeSim1)))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0")))
            whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(flowOf(mockUsage))
            whenever(simUsageResetRepository.ensureTrackingWindowInitialized(any())).thenReturn(true)
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(flowOf(1000L))
            whenever(baselineManager.getRetryCount("sim_0")).thenReturn(Result.success(null))
            whenever(baselineManager.getCurrentMobileBytes(1000L)).thenReturn(StatsResult.Success(2000L))
            whenever(simChangeHandler.withSimChangeLock<String?>(any())).thenAnswer {
                val block = it.arguments[0] as suspend () -> String?
                runBlocking { block() }
            }
            whenever(simChangeHandler.handleSimChangeIfNeeded(any(), any())).thenReturn("sim_0")
            whenever(targetResolver.resolve(any())).thenReturn(
                TrafficTargetResolver.TargetResult.Success(TargetInfo(slot = 0, dataSubId = 1)),
            )
            whenever(
                deltaCalculator.updateTrafficBaselineAndUsage(
                    eq("sim_0"),
                    eq(2000L),
                    eq(1000L),
                    any(),
                    eq(1000L),
                ),
            ).thenReturn(true)
        }
    }

    private val activeSim1 =
        ActiveSimInfo(
            identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, isStable = true),
            slotIndex = 0,
            subscriptionId = 1,
            iccId = "sim_0",
            displayName = "SIM 1",
            carrierName = "Carrier 1",
            isDefaultData = true,
            isDefaultVoice = true,
        )

    private val unstableSim =
        ActiveSimInfo(
            identity = SimIdentity("sim_0", 1, 0, IdentitySource.ICCID, isStable = false),
            slotIndex = 0,
            subscriptionId = 1,
            iccId = "sim_0",
            displayName = "SIM 1",
            carrierName = "Carrier 1",
            isDefaultData = true,
            isDefaultVoice = true,
        )

    @Test
    fun `empty active SIMs returns Skipped`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `stale input from target resolver returns Skipped`() =
        runTest {
            whenever(targetResolver.resolve(any())).thenReturn(TrafficTargetResolver.TargetResult.StaleInput)
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `no default data SIM returns Skipped`() =
        runTest {
            whenever(targetResolver.resolve(any())).thenReturn(TrafficTargetResolver.TargetResult.NoDefaultData)
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `no active SIMs from target resolver returns Skipped`() =
        runTest {
            whenever(targetResolver.resolve(any())).thenReturn(TrafficTargetResolver.TargetResult.NoActiveSims)
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `target identity no longer active returns Skipped`() =
        runTest {
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("other_sim")))
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `unstable identity returns Skipped`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(unstableSim)))
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `simChangeHandler returning null returns Skipped`() =
        runTest {
            whenever(simChangeHandler.handleSimChangeIfNeeded(any(), any())).thenReturn(null)
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `window mismatch in update write returns Skipped`() =
        runTest {
            whenever(
                deltaCalculator.updateTrafficBaselineAndUsage(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                ),
            ).thenReturn(false)
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Skipped, outcome)
        }

    @Test
    fun `successful delta application returns Completed`() =
        runTest {
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Completed, outcome)
        }

    @Test
    fun `delta below threshold returns Completed`() =
        runTest {
            whenever(
                deltaCalculator.updateTrafficBaselineAndUsage(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                ),
            ).thenReturn(true)
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertEquals(TrafficUpdateOutcome.Completed, outcome)
        }

    @Test
    fun `permission missing error returns Failed`() =
        runTest {
            whenever(permissionChecker.checkPermissions(any())).thenAnswer {
                val callback = it.arguments[0] as (TrackingStatus) -> Unit
                callback(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, "No permission"))
                false
            }
            val tracker = createTracker(this)
            val outcome = tracker.updateTrafficUsage()
            assertTrue(outcome is TrafficUpdateOutcome.Failed)
            assertEquals(TrackingStatus.ErrorType.PERMISSION_MISSING, (outcome as TrafficUpdateOutcome.Failed).type)
        }

    @Test
    fun `concurrent update calls return independent outcomes`() =
        runTest {
            val tracker = createTracker(this)

            val deferred1 = async { tracker.updateTrafficUsage() }
            val outcome1 = deferred1.await()

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            val deferred2 = async { tracker.updateTrafficUsage() }
            val outcome2 = deferred2.await()

            assertEquals(TrafficUpdateOutcome.Completed, outcome1)
            assertEquals(TrafficUpdateOutcome.Skipped, outcome2)
        }
}
