package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import android.telephony.SubscriptionInfo
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.domain.InvalidSlotException
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import com.github.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

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
    fun `adjustMinutes keeps limit unchanged and recalculates used balance`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flowOf(500))
            whenever(
                simUsageRepository.getSimUsage(identity),
            ).thenReturn(flowOf(createSimUsage(minutesUsed = 100)))

            // limit = 500, target remaining = 450 -> newUsed = 500 - 450 = 50
            usageUseCase.adjustMinutes(0, 450)

            verify(simUsageAdjustmentRepository).updateSimStats(
                identity = identity,
                minutesUsed = 50,
                trafficUsedMb = 0f,
                manualAdjustment = 0,
            )
        }

    @Test
    fun `adjustTraffic keeps limit unchanged and recalculates used traffic`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240)) // 10 GB
            whenever(
                simUsageRepository.getSimUsage(identity),
            ).thenReturn(flowOf(createSimUsage(trafficUsedMb = 2048f))) // 2 GB

            // limit = 10 GB, target remaining = 5 GB -> newUsedMb = (10 - 5) * 1024 = 5120 MB
            usageUseCase.adjustTraffic(0, 5f)

            verify(simUsageAdjustmentRepository).updateSimStats(
                identity = identity,
                minutesUsed = 0,
                trafficUsedMb = 5120f,
                manualAdjustment = 0,
            )
            verify(simUsageAdjustmentRepository).setTrafficAdjustment(identity, 0f)
        }

    @Test
    fun `adjustMinutes rethrows InvalidSlotException from repository`() =
        runTest {
            whenever(simIdentityRepository.getIdentity(99)).thenThrow(InvalidSlotException(99))

            val result = runCatching { usageUseCase.adjustMinutes(99, 100) }
            assertTrue(result.exceptionOrNull() is InvalidSlotException)
        }

    @Test
    fun `resetCounters rethrows IOException from repository`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simUsageResetRepository.resetSimUsageManual(identity)).thenAnswer {
                throw IOException("Storage error")
            }

            val result = runCatching { usageUseCase.resetCounters(0) }
            assertTrue(result.exceptionOrNull() is IOException)
        }

    @Test
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

            val result = runCatching { usageUseCase.refreshOperatorInfo() }
            assertTrue(result.exceptionOrNull() is IOException)
        }

    @Test
    fun `adjustMinutes fails on limit read timeout`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            // Mock timeout: flow that never emits
            whenever(simLimitsRepository.getMonthlyLimit(identity)).thenReturn(flow { awaitCancellation() })
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flowOf(createSimUsage()))

            val result = runCatching { usageUseCase.adjustMinutes(0, 450) }
            assertTrue(result.exceptionOrNull() is IOException)
        }

    @Test
    fun `adjustTraffic fails on usage read timeout`() =
        runTest {
            val identity = "sim_0"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)
            whenever(simLimitsRepository.getTrafficLimit(identity)).thenReturn(flowOf(10240))
            // Mock timeout: flow that never emits
            whenever(simUsageRepository.getSimUsage(identity)).thenReturn(flow { awaitCancellation() })

            val result = runCatching { usageUseCase.adjustTraffic(0, 5f) }
            assertTrue(result.exceptionOrNull() is IOException)
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
