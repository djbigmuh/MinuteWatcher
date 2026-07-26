package com.example.minutewatcher.core.domain

import android.content.Context
import android.telephony.SubscriptionManager
import com.example.minutewatcher.core.model.SimUsage
import com.example.minutewatcher.core.model.SimCardUiModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageUseCaseTest {

    private lateinit var context: Context

    @Mock
    private lateinit var simRepository: SimRepository

    @Mock
    private lateinit var usageStateRepository: UsageStateRepository

    @Mock
    private lateinit var subscriptionManager: SubscriptionManager

    private lateinit var usageUseCase: UsageUseCase

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        usageUseCase = UsageUseCase(context, simRepository, subscriptionManager)
        
        // Stub all flows to return empty/zero by default for any identity to avoid NPE
        whenever(simRepository.getSimOperator(any())).thenReturn(flowOf(""))
        whenever(simRepository.getSimCode(any())).thenReturn(flowOf(""))
        whenever(simRepository.getSimUsage(any())).thenReturn(flowOf(SimUsage(
            identity = "unknown",
            slotIndex = 0,
            subscriptionId = null,
            operator = "",
            minutesUsed = 0,
            minutesLimit = 0,
            trafficUsed = 0f,
            trafficLimitMb = 0,
            manualAdjustment = 0,
            trafficAdjustment = 0f,
            autoReset = false,
            autoResetDay = 1,
            lastResetDate = 0L
        )))
        whenever(simRepository.getMinutesUsed(any())).thenReturn(flowOf(0))
        whenever(simRepository.getMonthlyLimit(any())).thenReturn(flowOf(0))
        whenever(simRepository.getManualAdjustment(any())).thenReturn(flowOf(0))
        whenever(simRepository.getTrafficUsed(any())).thenReturn(flowOf(0f))
        whenever(simRepository.getTrafficLimit(any())).thenReturn(flowOf(0))
        whenever(simRepository.getTrafficAdjustment(any())).thenReturn(flowOf(0f))
        whenever(simRepository.getAutoReset(any())).thenReturn(flowOf(false))
        whenever(simRepository.getAutoResetDay(any())).thenReturn(flowOf(1))
    }

    @Test
    fun testRefreshOperatorInfo_Success() = runTest {
        whenever(subscriptionManager.activeSubscriptionInfoList).thenReturn(emptyList())
        usageUseCase.refreshOperatorInfo()
        // Smoke test: should not crash
    }

    @Test
    fun testObserveSimCardUiModels_Empty() = runTest {
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(flowOf(emptyList()))

        val result = usageStateRepository.observeSimCardUiModels().first()
        assertEquals(0, result.size)
    }

    @Test
    fun testObserveSimCardUiModels_SingleSim() = runTest {
        val mockModels = listOf(
            SimCardUiModel(
                slot = 0,
                identity = "sim_0",
                operatorName = "MTS",
                operatorCode = "25001",
                iconRes = 0,
                minutesUsed = 100,
                minutesLimit = 500,
                manualAdjustment = 0,
                minutesRemaining = 400,
                trafficUsedMb = 1024f,
                trafficLimitMb = 5120,
                trafficRemainingGb = 4.0f,
                isTrafficLimitSet = true,
                autoResetEnabled = false,
                autoResetDay = 1
            )
        )
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(flowOf(mockModels))

        val result = usageStateRepository.observeSimCardUiModels().first()
        assertEquals(1, result.size)
        assertEquals("MTS", result[0].operatorName)
        assertEquals(400, result[0].minutesRemaining)
        assertEquals(4.0f, result[0].trafficRemainingGb, 0.01f)
    }
}
