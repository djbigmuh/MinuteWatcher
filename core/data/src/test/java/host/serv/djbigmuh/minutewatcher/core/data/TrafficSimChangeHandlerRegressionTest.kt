package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficSimChangeHandlerRegressionTest {
    private val callSessionRepository: CallSessionRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val baselineManager: TrafficBaselineManager = mock()

    private lateinit var handler: TrafficSimChangeHandler

    @Before
    fun setup() {
        initTestLogger()
        handler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                baselineManager,
            )
    }

    @Test
    fun `regression - should reset baseline if lastDataSubId loading times out`() =
        runTest {
            // 1. Setup repository to hang, causing a timeout
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(
                flow {
                    delay((Constants.Flow.LONG_TIMEOUT_MS + 1000).milliseconds)
                    emit(1)
                },
            )

            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_new")
            val target = TargetInfo(slot = 0, dataSubId = 2)

            // 2. Call handleSimChangeIfNeeded
            val result = handler.handleSimChangeIfNeeded(target) { }

            // BUG: result will NOT be null because isSimChanged will be false (saved == -1)
            // EXPECTED: result should be null (skip delta) and baseline should be reset
            assertNull("Should skip delta if previous subId is unknown", result)

            // Verify baseline was reset for the new identity
            verify(baselineManager).resetBaseline(eq("sim_new"), any(), eq(true), any())

            // Verify current subId was committed
            verify(callSessionRepository).setLastDataSubId(2)
        }
}
