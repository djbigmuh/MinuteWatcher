package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.android.util.PermissionManager
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ManualResetTest {
    private val context: Context = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simLimitsRepository: SimLimitsRepository = mock()
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
                trafficTracker,
                permissionManager,
            )
    }

    @Test
    fun `manual reset clears adjustments and traffic baseline`() =
        runTest {
            val identity = "test-sim"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)

            usageUseCase.resetCounters(0)

            verify(simUsageRepository).resetSimUsageManual(identity)
            verify(trafficTracker).resetBaseline(identity)
        }
}
