package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class UsageUseCaseImplValidationTest {
    private val context: Context = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageAdjustmentRepository: SimUsageAdjustmentRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val trafficTracker: TrafficTracker = mock()
    private val permissionManager: PermissionManager = mock()
    private lateinit var usageUseCase: UsageUseCaseImpl

    @Before
    fun setup() {
        usageUseCase =
            UsageUseCaseImpl(
                context,
                simIdentityRepository,
                simUsageRepository,
                simUsageAdjustmentRepository,
                simLimitsRepository,
                simUsageResetRepository,
                trafficTracker,
                permissionManager,
            )
    }

    @Test
    fun `adjustMinutes clamps negative remaining to zero`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flowOf(100))
            whenever(
                simUsageRepository.getSimUsage(identity),
            ).thenReturn(flowOf(createSimUsage(minutesUsed = 0)))

            // target = -50 (clamped to 0)
            // formula: (0 - 100) + 0 = -100
            usageUseCase.adjustMinutes(0, -50)

            verify(simUsageAdjustmentRepository).setManualAdjustment(identity, -100)
        }

    @Test
    fun `adjustMinutes clamps excessive remaining to MAX_MINUTES_LIMIT`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flowOf(0))
            whenever(
                simUsageRepository.getSimUsage(identity),
            ).thenReturn(flowOf(createSimUsage(minutesUsed = 0)))

            usageUseCase.adjustMinutes(0, Constants.MAX_MINUTES_LIMIT + 1000)

            verify(simUsageAdjustmentRepository).setManualAdjustment(identity, Constants.MAX_MINUTES_LIMIT)
        }

    @Test
    fun `adjustTraffic clamps negative remainingGb to zero`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240)) // 10 GB
            whenever(
                simUsageRepository.getSimUsage(identity),
            ).thenReturn(flowOf(createSimUsage(trafficUsedMb = 0f)))

            // target = -1f (clamped to 0)
            // limitGb = 10, usedGb = 0
            // adj = 0 - (10 - 0) = -10
            usageUseCase.adjustTraffic(0, -1f)

            verify(simUsageAdjustmentRepository).setTrafficAdjustment(identity, -10f)
        }

    private fun createSimUsage(
        minutesUsed: Int = 0,
        trafficUsedMb: Float = 0f,
    ) = SimUsage(
        identity = "sim_0",
        slotIndex = 0,
        subscriptionId = 1,
        operator = "MTS",
        minutesUsed = minutesUsed,
        minutesLimit = 0,
        trafficUsedMb = trafficUsedMb,
        trafficLimitMb = 0,
        manualAdjustment = 0,
        trafficAdjustmentGb = 0f,
        autoReset = true,
        autoResetDay = 1,
        lastResetDate = 0L,
        rolloverEnabled = false,
    )
}
