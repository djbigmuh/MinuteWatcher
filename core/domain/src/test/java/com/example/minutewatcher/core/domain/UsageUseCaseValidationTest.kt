package com.example.minutewatcher.core.domain

import android.content.Context
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.android.util.PermissionManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UsageUseCaseValidationTest {
    private val context: Context = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private lateinit var usageUseCase: UsageUseCase

    @Before
    fun setup() {
        usageUseCase = UsageUseCase(
            context,
            simIdentityRepository,
            simUsageRepository,
            simLimitsRepository,
            permissionManager
        )
    }

    @Test
    fun `adjustMinutes clamps negative remaining to zero`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flowOf(100))
            whenever(simUsageRepository.getMinutesUsed(identity)).thenReturn(flowOf(0))

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
            whenever(simUsageRepository.getMinutesUsed(identity)).thenReturn(flowOf(0))

            usageUseCase.adjustMinutes(0, Constants.MAX_MINUTES_LIMIT + 1000)

            verify(simUsageRepository).setManualAdjustment(identity, Constants.MAX_MINUTES_LIMIT)
        }

    @Test
    fun `adjustTraffic clamps negative remainingGb to zero`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240)) // 10 GB
            whenever(simUsageRepository.getTrafficUsed(identity)).thenReturn(flowOf(0f))

            // target = -1f (clamped to 0)
            // limitGb = 10, usedGb = 0
            // adj = 0 - (10 - 0) = -10
            usageUseCase.adjustTraffic(0, -1f)

            verify(simUsageRepository).setTrafficAdjustment(identity, -10f)
        }
}
