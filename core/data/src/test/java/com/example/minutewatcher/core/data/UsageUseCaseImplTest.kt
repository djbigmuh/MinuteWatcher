package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UsageUseCaseImplTest {
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
    fun `refreshOperatorInfo updates sim info for active subscriptions`() =
        runTest {
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.displayName).thenReturn("Operator A")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")

            usageUseCase.refreshOperatorInfo()

            verify(simIdentityRepository).setSimOperator(eq("sim_0"), any())
        }

    @Test
    fun `adjustMinutes calls repository with correct adjustment`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flowOf(500))
            whenever(
                simUsageRepository.getSimUsage(identity),
            ).thenReturn(flowOf(createSimUsage(identity, minutesUsed = 100)))

            // target remaining = 450
            // formula: (target - limit) + used = (450 - 500) + 100 = 50
            usageUseCase.adjustMinutes(0, 450)

            verify(simUsageAdjustmentRepository).setManualAdjustment(identity, 50)
        }

    @Test
    fun `adjustTraffic calls repository with correct adjustment`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240)) // 10 GB
            whenever(
                simUsageRepository.getSimUsage(identity),
            ).thenReturn(flowOf(createSimUsage(identity, trafficUsed = 2048f))) // 2 GB

            // target remaining = 5 GB
            // limitGb = 10, usedGb = 2
            // current remaining = 8 GB
            // adj = target - (limit - used) = 5 - (10 - 2) = -3
            usageUseCase.adjustTraffic(0, 5f)

            verify(simUsageAdjustmentRepository).setTrafficAdjustment(identity, -3f)
        }

    private fun createSimUsage(
        id: String,
        minutesUsed: Int = 0,
        trafficUsed: Float = 0f,
    ) = SimUsage(
        identity = id,
        slotIndex = 0,
        subscriptionId = 1,
        operator = "MTS",
        minutesUsed = minutesUsed,
        minutesLimit = 0,
        trafficUsed = trafficUsed,
        trafficLimitMb = 0,
        manualAdjustment = 0,
        trafficAdjustment = 0f,
        autoReset = true,
        autoResetDay = 1,
        lastResetDate = 0L,
        rolloverEnabled = false,
    )
}
