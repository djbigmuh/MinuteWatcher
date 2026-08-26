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
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerRetryTest {
    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 1700000000000L)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var tracker: TrafficTracker

    @Before
    fun setup() {
        initTestLogger()
        setupPermissionMocks()

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
    }

    @Test
    fun `fetchActiveSims retries with delay and succeeds after long wait`() =
        runTest(testDispatcher) {
            val neverEmittingFlow = MutableSharedFlow<List<ActiveSimInfo>>()
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

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(neverEmittingFlow)

            val job = launch { tracker.updateTrafficUsage() }

            // 1. Initial attempt times out after LONG_TIMEOUT_MS
            advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
            runCurrent()

            // 2. Delay of 1000ms
            advanceTimeBy(1000.milliseconds)
            runCurrent()

            // 3. Second attempt with DOUBLE timeout (Total elapsed: LONG + 1000 + almost 2*LONG)
            advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) // Partial progress through second attempt
            runCurrent()

            neverEmittingFlow.emit(activeSims)
            runCurrent()

            // If it succeeded, it should NOT be in Error status from SimListTimeout
            val status = tracker.status.value
            assertTrue(
                "Status should not be Error(SIM list read timeout), but was $status",
                status !is TrackingStatus.Error || status.message != "SIM list read timeout",
            )

            job.cancel()
        }

    @Test
    fun `fetchActiveSims sets Error status after two failed attempts`() =
        runTest(testDispatcher) {
            val neverEmittingFlow = MutableSharedFlow<List<ActiveSimInfo>>()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(neverEmittingFlow)

            val job = launch { tracker.updateTrafficUsage() }

            // 1. Initial attempt: LONG_TIMEOUT_MS
            advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS.milliseconds)
            runCurrent()

            // 2. Delay: 1000ms
            advanceTimeBy(1000.milliseconds)
            runCurrent()

            // 3. Second attempt: LONG_TIMEOUT_MS * 2
            advanceTimeBy((Constants.Flow.LONG_TIMEOUT_MS * 2).milliseconds)
            runCurrent()

            // Assert
            val status = tracker.status.value
            assertTrue("Expected Error status, but got $status", status is TrackingStatus.Error)
            assertEquals("SIM list read timeout", (status as TrackingStatus.Error).message)

            job.cancel()
        }
}
