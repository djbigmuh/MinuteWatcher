package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ResetManagerCancellationTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider = FixedTimeProvider()

    private lateinit var resetManager: ResetManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        initTestLogger()
        resetManager =
            ResetManager(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                trafficTracker,
                dataChangeNotifier,
                timeProvider,
            )
    }

    @Test
    fun `performAutoReset cancellation updates cooldown and preserves state consistency`() =
        runTest(testDispatcher) {
            val identity = "sim_1"
            val simInfo = createSimInfo(identity)
            val usage = createSimUsage(identity)

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(listOf(simInfo)))

            val reachedCheck = CompletableDeferred<Unit>()
            var usageFetchCount = 0
            whenever(simUsageRepository.getSimUsage(identity)).thenAnswer {
                usageFetchCount++
                reachedCheck.complete(Unit)
                flowOf(usage)
            }

            // 1. Run first attempt and cancel it while suspended in usage fetch
            val job =
                launch {
                    resetManager.performAutoReset(false)
                }

            withTimeoutOrNull(30.seconds) { reachedCheck.await() }
                ?: throw AssertionError("Timed out waiting for reachedCheck")
            runCurrent()
            job.cancelAndJoin()

            assertEquals("Should have fetched usage once", 1, usageFetchCount)

            // 2. Try to run again immediately. It should be skipped due to cooldown from finally.
            resetManager.performAutoReset(false)
            runCurrent()
            assertEquals("Should NOT have fetched usage again due to cooldown", 1, usageFetchCount)

            // 3. Advance time past cooldown
            timeProvider.advance(46 * 60 * 1000) // 46 minutes

            resetManager.performAutoReset(false)
            runCurrent()
            assertEquals("Should have fetched usage again after cooldown", 2, usageFetchCount)
        }

    private fun createSimInfo(identity: String) =
        ActiveSimInfo(
            identity = SimIdentity(identity, 1, 0, IdentitySource.ICCID, true),
            subscriptionId = 1,
            slotIndex = 0,
            iccId = identity,
            displayName = "SIM",
            carrierName = "Carrier",
            mccMnc = "25001",
            isDefaultData = true,
            isDefaultVoice = true,
        )

    private fun createSimUsage(identity: String) =
        SimUsage(
            identity = identity,
            slotIndex = 0,
            subscriptionId = 1,
            operator = "MTS",
            minutesUsed = 0,
            minutesLimit = 100,
            trafficUsedMb = 0f,
            trafficLimitMb = 1000,
            manualAdjustment = 0,
            trafficAdjustmentGb = 0f,
            autoReset = true,
            autoResetDay = 1,
            lastResetDate = 0L,
        )
}
