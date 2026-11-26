package com.github.djbigmuh.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import com.github.djbigmuh.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import com.github.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficOvercountTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 2000000000000L)

    private lateinit var simUsageResetRepository: SimUsageResetRepository
    private lateinit var simUsageRepository: SimUsageRepository

    private val sim1Identity = "sim_1"
    private val sim2Identity = "sim_2"

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    @Before
    fun setup() =
        runTest(testDispatcher) {
            initTestLogger()
            val ds = InMemoryPreferencesDataStore()
            val repo = SimUsageRepositoryImpl(ds, timeProvider, SimUsageMapper(), SimUsageResetLogic())
            simUsageResetRepository = repo
            simUsageRepository = repo

            setupPermissionMocks()
            setupIdentityMocks()
            whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(true)
        }

    private fun setupPermissionMocks() {
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        val mockSubInfo1 =
            mock<SubscriptionInfo> {
                on { subscriptionId } doReturn 1
                on { simSlotIndex } doReturn 0
            }
        val mockSubInfo2 =
            mock<SubscriptionInfo> {
                on { subscriptionId } doReturn 2
                on { simSlotIndex } doReturn 1
            }
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo1, mockSubInfo2))
    }

    private suspend fun setupIdentityMocks() {
        whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(1))
        whenever(simIdentityRepository.getIdentity(0)).thenReturn(sim1Identity)
        whenever(simIdentityRepository.getIdentity(1)).thenReturn(sim2Identity)
        val activeSims =
            listOf(
                ActiveSimInfo(
                    identity = SimIdentity(sim1Identity, 1, 0, IdentitySource.ICCID, isStable = true),
                    slotIndex = 0,
                    subscriptionId = 1,
                    iccId = "icc_1",
                    displayName = "SIM 1",
                    carrierName = "C1",
                    isDefaultData = true,
                    isDefaultVoice = true,
                ),
                ActiveSimInfo(
                    identity = SimIdentity(sim2Identity, 2, 1, IdentitySource.ICCID, isStable = true),
                    slotIndex = 1,
                    subscriptionId = 2,
                    iccId = "icc_2",
                    displayName = "SIM 2",
                    carrierName = "C2",
                    isDefaultData = false,
                    isDefaultVoice = false,
                ),
            )
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
        whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf(sim1Identity, sim2Identity)))
    }

    private fun TestScope.createTracker(): TrafficTracker {
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
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                permissionManager,
                baselineManager,
                deltaCalculator,
                simChangeHandler,
                TrafficTargetResolver(permissionManager),
                TrafficPermissionChecker(permissionManager),
                permissionStateNotifier,
                dataChangeNotifier,
                timeProvider,
            )
        return TrafficTracker(deps, backgroundScope).apply { ensureStarted() }
    }

    private suspend fun TestScope.startTracker(): TrafficTracker {
        val t = createTracker()
        testDispatcher.scheduler.advanceUntilIdle()
        clearInvocations(
            simIdentityRepository,
            permissionManager,
            networkStatsProvider,
            callSessionRepository,
            dataChangeNotifier,
        )
        return t
    }

    @Test
    fun `reproduce overcount on SIM switch`() =
        runTest(testDispatcher) {
            val tracker = startTracker()

            // 1. Setup SIM 1 with some initial state in DataStore
            simUsageResetRepository.ensureTrackingWindowInitialized(sim1Identity)
            val u1 = simUsageRepository.getSimUsage(sim1Identity).first()
            simUsageResetRepository.setLastTotalBytes(sim1Identity, 100L * Constants.BYTES_PER_MB, u1.lastResetDate)

            // SIM 1 is active. Let's say it has 500MB used.
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(
                StatsResult.Success(
                    500L * Constants.BYTES_PER_MB,
                ),
            )

            tracker.updateTrafficUsage()
            testDispatcher.scheduler.advanceUntilIdle()

            // Should increment SIM 1 by 400MB
            val usage1 = simUsageRepository.getSimUsage(sim1Identity).first()
            assertEquals(400f, usage1.trafficUsedMb, 0.01f)

            // 2. Switch default data to SIM 2.
            simUsageResetRepository.ensureTrackingWindowInitialized(sim2Identity)
            val u2 = simUsageRepository.getSimUsage(sim2Identity).first()
            simUsageResetRepository.setLastTotalBytes(sim2Identity, 0L, u2.lastResetDate)

            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(1))

            // And it SHOULD query lastResetDate for SIM 2 instead.
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(
                StatsResult.Success(
                    1000L * Constants.BYTES_PER_MB.toLong(),
                ),
            )

            tracker.updateTrafficUsage()
            testDispatcher.scheduler.advanceUntilIdle()

            // Verify baseline for SIM 2 was set correctly.
            val baseline2 = simUsageResetRepository.getLastTotalBytes(sim2Identity).first()
            assertEquals(1000L * Constants.BYTES_PER_MB.toLong(), baseline2)

            // 3. Next update for SIM 2. Now it has 1050MB.
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(
                StatsResult.Success(
                    1050L * Constants.BYTES_PER_MB,
                ),
            )

            tracker.updateTrafficUsage()
            testDispatcher.scheduler.advanceUntilIdle()

            // Correct! Delta = 1050 - 1000 = 50MB.
            val usage2 = simUsageRepository.getSimUsage(sim2Identity).first()
            assertEquals(50f, usage2.trafficUsedMb, 0.01f)
        }

    @Test
    fun `retry path uses correct window instead of zero`() =
        runTest(testDispatcher) {
            val tracker = startTracker()
            val identity = sim1Identity

            // 1. Setup SIM 1 with initial state and a retry state.
            simUsageResetRepository.ensureTrackingWindowInitialized(identity)
            simUsageResetRepository.setBaselineRetryCount(identity, 1)

            // 2. Mock stats result for the full window.
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(
                StatsResult.Success(
                    1000L * Constants.BYTES_PER_MB,
                ),
            )

            tracker.updateTrafficUsage()
            testDispatcher.scheduler.advanceUntilIdle()

            // 3. Verify that baseline was set to 1000MB.
            val baseline = simUsageResetRepository.getLastTotalBytes(identity).first()
            assertEquals(1000L * Constants.BYTES_PER_MB.toLong(), baseline)

            // Verification: retry count should have been cleared.
            val retryCount = simUsageResetRepository.getBaselineRetryCount(identity).first()
            assertEquals(null, retryCount)
        }

    @Test
    fun `traffic tracker ignores update when clock is rewound and continues when clock advances`() =
        runTest(testDispatcher) {
            val tracker = startTracker()
            val identity = sim1Identity

            // 1. Initialize tracking window, set baseline B and accumulated usage U
            simUsageResetRepository.ensureTrackingWindowInitialized(identity)
            val initialUsage = simUsageRepository.getSimUsage(identity).first()
            val baselineB = 1000L * Constants.BYTES_PER_MB
            val usageU = 0f
            simUsageResetRepository.setLastTotalBytes(identity, baselineB, initialUsage.lastResetDate)

            // 2. Move wall-clock backward (now < lastResetDate)
            timeProvider.setTime(initialUsage.lastResetDate - 1000L)

            // Even if provider mock returns Success(0)
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(
                StatsResult.Success(0L),
            )

            tracker.updateTrafficUsage()
            testDispatcher.scheduler.advanceUntilIdle()

            // 3. Verify lastTotalBytes remains B, trafficUsedMb remains U
            val usageAfterRewind = simUsageRepository.getSimUsage(identity).first()
            val baselineAfterRewind = simUsageResetRepository.getLastTotalBytes(identity).first()
            assertEquals(baselineB, baselineAfterRewind)
            assertEquals(usageU, usageAfterRewind.trafficUsedMb, 0.01f)

            // 4. Return and advance wall-clock forward past original point
            timeProvider.setTime(initialUsage.lastResetDate + 100000L)
            val additionalBytes = 50L * Constants.BYTES_PER_MB
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(
                StatsResult.Success(baselineB + additionalBytes),
            )

            tracker.updateTrafficUsage()
            testDispatcher.scheduler.advanceUntilIdle()

            // 5. Verify trafficUsedMb == U + 50 MB, lastTotalBytes == B + 50 MB
            val usageAfterAdvance = simUsageRepository.getSimUsage(identity).first()
            val baselineAfterAdvance = simUsageResetRepository.getLastTotalBytes(identity).first()
            assertEquals(baselineB + additionalBytes, baselineAfterAdvance)
            assertEquals(50f, usageAfterAdvance.trafficUsedMb, 0.01f)
        }
}
