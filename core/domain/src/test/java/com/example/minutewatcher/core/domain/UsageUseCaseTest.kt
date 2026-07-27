package com.example.minutewatcher.core.domain

import android.content.Context
import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UsageUseCaseTest {
    private val context: Context = mock()
    private val simRepository: SimRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private lateinit var usageUseCase: UsageUseCase

    @Before
    fun setup() {
        usageUseCase = UsageUseCase(context, simRepository, permissionManager)
    }

    @Test
    fun `refreshOperatorInfo updates sim info for active subscriptions`() =
        runTest {
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.displayName).thenReturn("Operator A")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))
            whenever(simRepository.getIdentity(0)).thenReturn("sim_0")

            usageUseCase.refreshOperatorInfo()

            verify(simRepository).setSimOperator(eq("sim_0"), any())
        }

    @Test
    fun `adjustMinutes calls repository with correct adjustment`() =
        runTest {
            val identity = "sim_0"
            whenever(simRepository.getIdentity(0)).thenReturn(identity)
            whenever(simRepository.getMonthlyLimit(identity)).thenReturn(flowOf(500))
            whenever(simRepository.getMinutesUsed(identity)).thenReturn(flowOf(100))

            // target remaining = 450
            // formula: (target - limit) + used = (450 - 500) + 100 = 50
            usageUseCase.adjustMinutes(0, 450)

            verify(simRepository).setManualAdjustment(identity, 50)
        }

    @Test
    fun `adjustTraffic calls repository with correct adjustment`() =
        runTest {
            val identity = "sim_0"
            whenever(simRepository.getIdentity(0)).thenReturn(identity)
            whenever(simRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240)) // 10 GB
            whenever(simRepository.getTrafficUsed(identity)).thenReturn(flowOf(2048f)) // 2 GB

            // target remaining = 5 GB
            // limitGb = 10, usedGb = 2
            // current remaining = 8 GB
            // adj = target - (limit - used) = 5 - (10 - 2) = -3
            usageUseCase.adjustTraffic(0, 5f)

            verify(simRepository).setTrafficAdjustment(identity, -3f)
        }
}
