package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import org.mockito.stubbing.Answer
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerRegressionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
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

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
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
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(flowOf(1000L))
            whenever(simUsageResetRepository.getBaselineRetryCount("sim_0")).thenReturn(MutableStateFlow(1))

            whenever(simUsageResetRepository.setLastTotalBytes(eq("sim_0"), any())).thenAnswer(
                stubSuspend {
                    delay((Constants.Flow.SHORT_TIMEOUT_MS + 1000).milliseconds)
                },
            )

            tracker.updateTrafficUsage()

            val status = tracker.status.value
            assertTrue("Expected Error status with TIMEOUT code, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, (status as TrackingStatus.Error).code)
            verify(simUsageResetRepository, never()).setBaselineRetryCount(eq("sim_0"), eq(null))
        }

    @Test
    fun `granting missing permissions triggers immediate traffic usage update`() =
        runTest {
            val permissionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            whenever(permissionStateNotifier.changes).thenReturn(permissionChanges)

            whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(false)

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
            whenever(simUsageResetRepository.getLastTotalBytes("sim_0")).thenReturn(flowOf(1000L))
            whenever(simUsageResetRepository.getBaselineRetryCount("sim_0")).thenReturn(flowOf(null))
            whenever(networkStatsProvider.queryMobileBytes(any(), any())).thenReturn(StatsResult.Success(5000L))

            val tracker = createTracker(backgroundScope)

            withTimeout(5000.milliseconds) {
                while (true) {
                    val status = tracker.status.value
                    if (status is TrackingStatus.Error &&
                        status.type == TrackingStatus.ErrorType.PERMISSION_MISSING
                    ) {
                        break
                    }
                    delay(20.milliseconds)
                }
            }

            whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)
            permissionChanges.emit(Unit)

            withTimeout(5000.milliseconds) {
                while (tracker.status.value !is TrackingStatus.Active) {
                    delay(20.milliseconds)
                }
            }

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

    private fun <T> stubSuspend(block: suspend () -> T): Answer<Any?> =
        Answer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<T>
            val job =
                CoroutineScope(continuation.context).launch {
                    try {
                        val result = block()
                        continuation.resumeWith(Result.success(result))
                    } catch (e: Throwable) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }
            continuation.context[Job]?.invokeOnCompletion {
                job.cancel()
            }
            COROUTINE_SUSPENDED
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
