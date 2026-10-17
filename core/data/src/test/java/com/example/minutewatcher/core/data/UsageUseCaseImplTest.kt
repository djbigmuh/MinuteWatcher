package com.example.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.InvalidSlotException
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
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

            verify(simIdentityRepository).setSimOperator(eq("sim_0"), any(), eq(null))
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
            ).thenReturn(flowOf(createSimUsage(identity, trafficUsedMb = 2048f))) // 2 GB

            // target remaining = 5 GB
            // limitGb = 10, usedGb = 2
            // current remaining = 8 GB
            // adj = target - (limit - used) = 5 - (10 - 2) = -3
            usageUseCase.adjustTraffic(0, 5f)

            verify(simUsageAdjustmentRepository).setTrafficAdjustment(identity, -3f)
        }

    @Test(expected = InvalidSlotException::class)
    fun `adjustMinutes rethrows InvalidSlotException from repository`() =
        runTest {
            whenever(simIdentityRepository.getIdentity(99)).thenThrow(InvalidSlotException(99))

            usageUseCase.adjustMinutes(99, 100)
        }

    @Test(expected = IOException::class)
    fun `resetCounters rethrows IOException from repository`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageResetRepository.resetSimUsageManual(identity)).thenAnswer {
                throw IOException("Storage error")
            }

            usageUseCase.resetCounters(0)
        }

    @Test(expected = IOException::class)
    fun `refreshOperatorInfo rethrows IOException from repository`() =
        runTest {
            val subInfo = mock<SubscriptionInfo>()
            whenever(subInfo.simSlotIndex).thenReturn(0)
            whenever(subInfo.displayName).thenReturn("Operator A")
            whenever(subInfo.mccString).thenReturn("250")
            whenever(subInfo.mncString).thenReturn("01")
            whenever(permissionManager.getActiveSubscriptions()).thenReturn(listOf(subInfo))
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_0")
            whenever(simIdentityRepository.setSimOperator(any(), any(), eq(null))).thenAnswer {
                throw IOException("Write error")
            }

            usageUseCase.refreshOperatorInfo()
        }

    @Test(expected = IOException::class)
    fun `adjustMinutes fails on limit read timeout`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            // Mock timeout: flow that never emits
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flow { awaitCancellation() })
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage(identity)))

            usageUseCase.adjustMinutes(0, 450)
        }

    @Test(expected = IOException::class)
    fun `adjustTraffic fails on usage read timeout`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240))
            // Mock timeout: flow that never emits
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flow { awaitCancellation() })

            usageUseCase.adjustTraffic(0, 5f)
        }

    private fun createSimUsage(
        id: String,
        minutesUsed: Int = 0,
        trafficUsedMb: Float = 0f,
    ) = SimUsage(
        identity = id,
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
