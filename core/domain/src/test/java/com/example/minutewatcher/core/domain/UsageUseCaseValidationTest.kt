package com.example.minutewatcher.core.domain

import android.content.Context
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.PermissionManager
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class UsageUseCaseValidationTest {

    private val context: Context = mock()
    private val simRepository: SimRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private lateinit var usageUseCase: UsageUseCase

    @Before
    fun setup() {
        usageUseCase = UsageUseCase(context, simRepository, permissionManager)
    }

    @Test
    fun `adjustMinutes clamps negative remaining to zero`() = runTest {
        val slot = 0
        val identity = "sim_0"
        whenever(simRepository.getIdentity(slot)).thenReturn(identity)
        whenever(simRepository.getMonthlyLimit(identity)).thenReturn(flowOf(100))
        whenever(simRepository.getMinutesUsed(identity)).thenReturn(flowOf(10))

        usageUseCase.adjustMinutes(slot, -50)

        // expected safeRemaining = 0
        // adj = (0 - 100) + 10 = -90
        verify(simRepository).setManualAdjustment(identity, -90)
    }

    @Test
    fun `adjustMinutes clamps excessive remaining to max limit`() = runTest {
        val slot = 0
        val identity = "sim_0"
        whenever(simRepository.getIdentity(slot)).thenReturn(identity)
        whenever(simRepository.getMonthlyLimit(identity)).thenReturn(flowOf(100))
        whenever(simRepository.getMinutesUsed(identity)).thenReturn(flowOf(10))

        usageUseCase.adjustMinutes(slot, 999999)

        val expectedAdj = (Constants.MAX_MINUTES_LIMIT - 100) + 10
        verify(simRepository).setManualAdjustment(identity, expectedAdj)
    }
}
