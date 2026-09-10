package com.example.minutewatcher.core.data

import android.telephony.SubscriptionInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficOvercountTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val callSessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val timeProvider = FixedTimeProvider(currentTime = 2000000000000L)

    private lateinit var simUsageResetRepository: SimUsageResetRepository
    private lateinit var simUsageRepository: SimUsageRepository

    private val sim1Identity = "sim_1"
    private val sim2Identity = "sim_2"
    private val lastResetDate = 1000000000000L

    @Before
    fun setup() {
        initTestLogger()
        val ds =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(testDispatcher)) {
                tmpFolder.newFile("test_sim_usage_${System.nanoTime()}.preferences_pb")
            }
        val repo = SimUsageRepositoryImpl(ds, timeProvider, SimUsageMapper(), SimUsageResetLogic())
        simUsageResetRepository = repo
        simUsageRepository = repo

        setupPermissionMocks()
        setupIdentityMocks()
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
        runBlocking {
            whenever(dataChangeNotifier.notifyDataChanged(any(), any())).thenReturn(true)
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
        val mockSubInfo2 =
            mock<SubscriptionInfo> {
                on { subscriptionId } doReturn 2
                on { simSlotIndex } doReturn 1
            }
        whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(mockSubInfo1, mockSubInfo2))
    }

    private fun setupIdentityMocks() =
        runBlocking {
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
                        mccMnc = "25001",
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
                        mccMnc = "25002",
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
            TrafficErrorHandler(),
            TrafficPermissionChecker(permissionManager),
            permissionStateNotifier,
            dataChangeNotifier,
            backgroundScope,
        )
    }

    private fun TestScope.startTracker(): TrafficTracker {
        val t = createTracker()
        t.status.value // Trigger lazy initialization
        advanceUntilIdle()
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
            simUsageResetRepository.setLastResetDate(sim1Identity, lastResetDate)
            simUsageResetRepository.setLastTotalBytes(sim1Identity, 100L * Constants.BYTES_PER_MB)

            // SIM 1 is active. Let's say it has 500MB used.
            whenever(networkStatsProvider.queryMobileBytes(eq(lastResetDate), any())).thenReturn(
                StatsResult.Success(
                    500L * Constants.BYTES_PER_MB,
                ),
            )

            tracker.updateTrafficUsage()
            advanceUntilIdle()

            // Should increment SIM 1 by 400MB
            val usage1 = simUsageRepository.getSimUsage(sim1Identity).first()
            assertEquals(400f, usage1.trafficUsedMb, 0.01f)

            // 2. Switch default data to SIM 2.
            simUsageResetRepository.setLastResetDate(sim2Identity, lastResetDate)
            simUsageResetRepository.setLastTotalBytes(sim2Identity, 0L)

            whenever(permissionManager.getDefaultDataSubscriptionId()).thenReturn(2)
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flowOf(1))

            // And it SHOULD query lastResetDate for SIM 2 instead.
            whenever(networkStatsProvider.queryMobileBytes(eq(lastResetDate), any())).thenReturn(
                StatsResult.Success(
                    1000L * Constants.BYTES_PER_MB.toLong(),
                ),
            )

            tracker.updateTrafficUsage()
            advanceUntilIdle()

            // Verify baseline for SIM 2 was set correctly.
            val baseline2 = simUsageResetRepository.getLastTotalBytes(sim2Identity).first()
            assertEquals(1000L * Constants.BYTES_PER_MB.toLong(), baseline2)

            // 3. Next update for SIM 2. Now it has 1050MB.
            whenever(networkStatsProvider.queryMobileBytes(eq(lastResetDate), any())).thenReturn(
                StatsResult.Success(
                    1050L * Constants.BYTES_PER_MB,
                ),
            )

            tracker.updateTrafficUsage()
            advanceUntilIdle()

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
            simUsageResetRepository.setLastResetDate(identity, lastResetDate)
            simUsageResetRepository.setBaselineRetryCount(identity, 1)

            // 2. Mock stats result for the full window.
            whenever(networkStatsProvider.queryMobileBytes(eq(lastResetDate), any())).thenReturn(
                StatsResult.Success(
                    1000L * Constants.BYTES_PER_MB,
                ),
            )

            tracker.updateTrafficUsage()
            advanceUntilIdle()

            // 3. Verify that baseline was set to 1000MB.
            val baseline = simUsageResetRepository.getLastTotalBytes(identity).first()
            assertEquals(1000L * Constants.BYTES_PER_MB.toLong(), baseline)

            // Verification: retry count should have been cleared.
            val retryCount = simUsageResetRepository.getBaselineRetryCount(identity).first()
            assertEquals(null, retryCount)
        }
}
