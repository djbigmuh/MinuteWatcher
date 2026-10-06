package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.util.DataStoreRetryHelper
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerRegressionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeSimUsageResetRepository(
        val delegate: SimUsageResetRepository = mock(),
    ) : SimUsageResetRepository by delegate {
        var setLastTotalBytesDelayMs: Long = 0L

        override suspend fun setLastTotalBytes(
            identity: String,
            bytes: Long,
        ) {
            if (setLastTotalBytesDelayMs > 0) {
                delay(setLastTotalBytesDelayMs)
            } else {
                delegate.setLastTotalBytes(identity, bytes)
            }
        }
    }

    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepositoryDelegate: SimUsageResetRepository = mock()
    private val simUsageResetRepository = FakeSimUsageResetRepository(simUsageResetRepositoryDelegate)
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider =
        FixedTimeProvider(
            currentTime = 1700000000000L,
            currentRealtime = 100000L,
            currentUptime = 100000L,
        )

    private var hasUsageStatsPermission = true

    @Before
    fun setup() {
        initTestLogger()
        hasUsageStatsPermission = true
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenAnswer { hasUsageStatsPermission }
        whenever(permissionManager.isTelephonySupported()).thenReturn(true)
        whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(1)
        whenever(permissionStateNotifier.changes).thenReturn(MutableStateFlow(Unit))
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(flow { delay(Long.MAX_VALUE) })
        runBlocking {
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(1))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
        }
    }

    @Test
    fun `handleRetryAction baseline write timeout sets TIMEOUT error and preserves retry state`() =
        runTest {
            val tracker = createTracker(backgroundScope)
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
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0")))
            whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(flowOf(createSimUsage("sim_0")))
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(5000L))
            whenever(simUsageResetRepositoryDelegate.getLastTotalBytes("sim_0")).thenReturn(flowOf(1000L))
            whenever(simUsageResetRepositoryDelegate.getBaselineRetryCount("sim_0")).thenReturn(MutableStateFlow(1))

            simUsageResetRepository.setLastTotalBytesDelayMs = Constants.Flow.SHORT_TIMEOUT_MS + 1000

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue("Expected Error status with TIMEOUT code, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, (status as TrackingStatus.Error).code)
            verify(simUsageResetRepositoryDelegate, never()).setBaselineRetryCount(eq("sim_0"), eq(null))
        }

    @Test
    fun `granting missing permissions triggers immediate traffic usage update`() =
        runTest {
            val permissionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)

            var hasUsageStatsPermission = false
            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.hasUsageStatsPermission()).thenAnswer { hasUsageStatsPermission }
            whenever(permissionManager.isTelephonySupported()).thenReturn(true)

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
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flowOf(listOf("sim_0")))
            whenever(simUsageRepository.getSimUsage("sim_0")).thenReturn(flowOf(createSimUsage("sim_0")))
            whenever(simUsageResetRepositoryDelegate.getLastTotalBytes("sim_0")).thenReturn(flowOf(1000L))
            whenever(simUsageResetRepositoryDelegate.getBaselineRetryCount("sim_0")).thenReturn(flowOf(null))
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(5000L))

            val tracker = createTracker(backgroundScope)
            tracker.updateTrafficUsage()
            advanceUntilIdle()

            val initialStatus = tracker.status.value
            assertTrue(
                "Expected PERMISSION_MISSING error, but was $initialStatus",
                initialStatus is TrackingStatus.Error &&
                    initialStatus.type == TrackingStatus.ErrorType.PERMISSION_MISSING,
            )

            hasUsageStatsPermission = true
            permissionChanges.emit(Unit)
            tracker.updateTrafficUsage()
            advanceUntilIdle()

            val updatedStatus = tracker.status.value
            assertTrue(
                "Expected Active status after permission grant, but was $updatedStatus",
                updatedStatus is TrackingStatus.Active,
            )

            verify(networkStatsProvider, atLeastOnce()).queryMobileBytes(any(), any())
        }

    @Test
    fun `traffic update times out and sets TIMEOUT error when getActiveIdentities times out`() =
        runTest {
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
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(activeSims))
            whenever(simIdentityRepository.getActiveIdentities()).thenReturn(flow { delay(Long.MAX_VALUE) })

            val tracker = createTracker(backgroundScope)

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue(
                "Expected Error status with TIMEOUT code when getActiveIdentities times out, but was $status",
                status is TrackingStatus.Error && status.code == TrackingStatus.ErrorCode.TIMEOUT,
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
        val targetResolver = TrafficTargetResolver(permissionManager)

        val deps =
            createTrafficTrackerDependencies(
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
            )
        return TrafficTracker(deps, scope).apply { ensureStarted() }
    }

    private fun createSimUsage(identity: String) =
        SimUsage(
            identity = identity,
            slotIndex = 0,
            subscriptionId = 1,
            operator = "Carrier",
            minutesUsed = 0,
            minutesLimit = 500,
            trafficUsedMb = 0f,
            trafficLimitMb = 1000,
            manualAdjustment = 0,
            trafficAdjustmentGb = 0f,
            autoReset = true,
            autoResetDay = 1,
            lastResetDate = 1000000L,
        )
}
