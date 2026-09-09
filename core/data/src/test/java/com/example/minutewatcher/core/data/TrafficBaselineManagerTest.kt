package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TrafficBaselineManagerTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val timeProvider: TimeProvider = mock()
    private val networkStatsProvider: NetworkStatsProvider = mock()
    private lateinit var manager: TrafficBaselineManager

    @Before
    fun setup() {
        manager =
            TrafficBaselineManager(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                mock(), // permissionManager
                timeProvider,
                networkStatsProvider,
                mock(),
            )
    }

    @Test
    fun `getRetryCount delegates to repository`() =
        runTest {
            whenever(simUsageResetRepository.getBaselineRetryCount("id1")).thenReturn(flowOf(2))
            val count = manager.getRetryCount("id1")
            assertEquals(2, count)
        }

    @Test
    fun `setRetryCount delegates to repository`() =
        runTest {
            manager.setRetryCount("id1", 3)
            verify(simUsageResetRepository).setBaselineRetryCount("id1", 3)
        }

    @Test
    fun `removeRetry delegates to repository with null`() =
        runTest {
            manager.removeRetry("id1")
            verify(simUsageResetRepository).setBaselineRetryCount("id1", null)
        }

    @Test
    fun `cleanupRetries delegates to repository`() =
        runTest {
            val active = setOf("id1", "id2")
            manager.cleanupRetries(active)
            verify(simUsageResetRepository).cleanupBaselineRetries(active)
        }
}
