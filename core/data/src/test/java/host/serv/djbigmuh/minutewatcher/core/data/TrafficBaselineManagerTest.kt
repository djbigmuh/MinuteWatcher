package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TrafficBaselineManagerTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val timeProvider: TimeProvider = mock()
    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val dataStoreRetryHelper = DataStoreRetryHelper()
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
                dataStoreRetryHelper,
            )
    }

    @Test
    fun `getRetryCount delegates to repository`() =
        runTest {
            whenever(simUsageResetRepository.getBaselineRetryCount("id1")).thenReturn(flowOf(2))
            val count = manager.getRetryCount("id1")
            assertEquals(Result.success(2), count)
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

    @Test
    fun `getCurrentMobileBytes returns Unavailable when now is less than startTime`() =
        runTest {
            whenever(timeProvider.now()).thenReturn(100L)
            val result = manager.getCurrentMobileBytes(200L)
            assertEquals(StatsResult.Unavailable, result)
            verify(networkStatsProvider, never()).queryMobileBytes(any(), any())
        }

    @Test
    fun `getCurrentMobileBytes returns Error when startTime is zero or negative`() =
        runTest {
            val resultZero = manager.getCurrentMobileBytes(0L)
            assert(resultZero is StatsResult.Error)
            assertEquals(
                "startTime must be greater than 0, but was 0",
                (resultZero as StatsResult.Error).exception.message,
            )

            val resultNegative = manager.getCurrentMobileBytes(-1L)
            assert(resultNegative is StatsResult.Error)
            assertEquals(
                "startTime must be greater than 0, but was -1",
                (resultNegative as StatsResult.Error).exception.message,
            )

            verify(networkStatsProvider, never()).queryMobileBytes(any(), any())
        }

    @Test
    fun `getCurrentMobileBytes queries provider when now is greater than or equal to startTime`() =
        runTest {
            whenever(timeProvider.now()).thenReturn(300L)
            whenever(networkStatsProvider.queryMobileBytes(200L, 300L)).thenReturn(StatsResult.Success(1000L))
            val result = manager.getCurrentMobileBytes(200L)
            assertEquals(StatsResult.Success(1000L), result)
            verify(networkStatsProvider).queryMobileBytes(200L, 300L)
        }
}
