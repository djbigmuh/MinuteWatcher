package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UsageUseCaseImplValidationTest {
    private val context: Context = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
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
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity, minutesUsed = 0)))

            // target = -50 (clamped to 0)
            // formula: (0 - 100) + 0 = -100
            usageUseCase.adjustMinutes(0, -50)

            verify(simUsageRepository).setManualAdjustment(identity, -100)
        }

    @Test
    fun `adjustMinutes clamps excessive remaining to MAX_MINUTES_LIMIT`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flowOf(0))
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity, minutesUsed = 0)))

            usageUseCase.adjustMinutes(0, Constants.MAX_MINUTES_LIMIT + 1000)

            verify(simUsageRepository).setManualAdjustment(identity, Constants.MAX_MINUTES_LIMIT)
        }

    @Test
    fun `adjustTraffic clamps negative remainingGb to zero`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240)) // 10 GB
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity, trafficUsed = 0f)))

            // target = -1f (clamped to 0)
            // limitGb = 10, usedGb = 0
            // adj = 0 - (10 - 0) = -10
            usageUseCase.adjustTraffic(0, -1f)

            verify(simUsageRepository).setTrafficAdjustment(identity, -10f)
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
