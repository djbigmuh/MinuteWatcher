package host.serv.djbigmuh.minutewatcher.core.data

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
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
    fun `manual reset clears adjustments and traffic baseline`() =
        runTest {
            val identity = "test-sim"
            whenever(simIdentityRepository.getIdentity(0)).thenReturn(identity)

            usageUseCase.resetCounters(0)

            verify(simUsageResetRepository).resetSimUsageManual(identity)
            verify(trafficTracker).resetBaseline(identity)
        }
}
