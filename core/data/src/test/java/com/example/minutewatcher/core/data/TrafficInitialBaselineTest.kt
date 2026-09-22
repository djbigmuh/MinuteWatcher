package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficInitialBaselineTest {
    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 2000000000000L)

    private val sim1Identity = "sim_1"
    private val retryCounts = mutableMapOf<String, MutableStateFlow<Int?>>()

    private val testDispatcher = StandardTestDispatcher()
    private val appScope = CoroutineScope(testDispatcher + Job())

    @Before
    fun setup() {
        initTestLogger()
        setupPermissionMocks()
        setupIdentityMocks()
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())

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
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo1))
    }

    private fun setupIdentityMocks() =
        runBlocking {
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(1))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(sim1Identity)
            val activeSims =
                listOf(
                    ActiveSimInfo(
                        identity = SimIdentity(sim1Identity, 1, 0, IdentitySource.ICCID, isStable = true),
                        slotIndex = 0,
                        subscriptionId = 1,
                        iccId = "icc_1",
                        displayName = "SIM 1",
                        carrierName = "C1",
                        mccMnc = "25001",
                        isDefaultData = true,
                        isDefaultVoice = true,
                    ),
                )
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf(sim1Identity)))
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
                mock(),
            )
        val deltaCalculator = TrafficDeltaCalculator(simUsageResetRepository)
        val simChangeHandler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                baselineManager,
            )
        return TrafficTracker(
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
            mock(),
            timeProvider,
            appScope,
        )
    }

    @Test
    fun `new SIM with lastResetDate 0 is initialized correctly`() =
        runTest(testDispatcher) {
            val tracker = createTracker()

            // 1. Mock SIM usage with lastResetDate = 0
            val usageWithZero = createSimUsage(sim1Identity, 1, 0, lastResetDate = 0L)
            whenever(simUsageRepository.getSimUsage(sim1Identity)).thenReturn(flowOf(usageWithZero))
            whenever(simUsageResetRepository.getLastTotalBytes(sim1Identity)).thenReturn(flowOf(-1L))

            // 2. Perform update.
            // It SHOULD call ensureTrackingWindowInitialized.
            tracker.updateTrafficUsage()

            verify(simUsageResetRepository).ensureTrackingWindowInitialized(eq(sim1Identity))
        }

    @Test
    fun `traffic is counted correctly after initialization`() =
        runTest(testDispatcher) {
            val tracker = createTracker()
            val now = timeProvider.now()

            // Phase 1: Initialization
            whenever(
                simUsageRepository.getSimUsage(sim1Identity),
            ).thenReturn(flowOf(createSimUsage(sim1Identity, 1, 0, lastResetDate = 0L)))
            whenever(simUsageResetRepository.getLastTotalBytes(sim1Identity)).thenReturn(flowOf(-1L))

            // This will call ensureTrackingWindowInitialized, which will set lastResetDate = now and baseline = 0.
            tracker.updateTrafficUsage()

            // Phase 2: Consumption. Update mocks to reflect initialized state.
            whenever(
                simUsageRepository.getSimUsage(sim1Identity),
            ).thenReturn(flowOf(createSimUsage(sim1Identity, 1, 0, lastResetDate = now)))
            whenever(simUsageResetRepository.getLastTotalBytes(sim1Identity)).thenReturn(flowOf(0L))

            // Consumer 100MB. Window [now, now]. queryMobileBytes(now, now) SHOULD be 100MB in this scenario
            // because the window started AT 'now'.
            whenever(networkStatsProvider.queryMobileBytes(eq(now), any())).thenReturn(
                StatsResult.Success(
                    100L * Constants.BYTES_PER_MB,
                ),
            )

            tracker.updateTrafficUsage()

            // Verify that 100MB was added.
            verify(simUsageResetRepository).incrementTrafficAndSetBaseline(
                eq(sim1Identity),
                eq(100f),
                eq(
                    100L * Constants.BYTES_PER_MB,
                ),
            )
        }

    @Test
    fun `ensureTrackingWindowInitialized is idempotent`() =
        runTest(testDispatcher) {
            val dataStore = InMemoryPreferencesDataStore()
            val repository =
                SimUsageRepositoryImpl(
                    dataStore = dataStore,
                    timeProvider = timeProvider,
                    mapper = SimUsageMapper(),
                    resetLogic = SimUsageResetLogic(),
                )

            val firstInit = repository.ensureTrackingWindowInitialized(sim1Identity)
            assertTrue("First call should perform initialization", firstInit)

            val usageAfterFirst = repository.getSimUsage(sim1Identity).first()
            assertEquals(timeProvider.now(), usageAfterFirst.lastResetDate)

            // Advance time
            val oldNow = timeProvider.now()
            timeProvider.advance(1000)

            val secondInit = repository.ensureTrackingWindowInitialized(sim1Identity)
            assertTrue("Second call should NOT perform initialization", !secondInit)

            val usageAfterSecond = repository.getSimUsage(sim1Identity).first()
            assertEquals("lastResetDate should not have changed", oldNow, usageAfterSecond.lastResetDate)
        }

    private fun createSimUsage(
        id: String,
        subId: Int,
        slot: Int,
        lastResetDate: Long,
    ) = SimUsage(
        identity = id,
        slotIndex = slot,
        subscriptionId = subId,
        operator = "Op",
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
