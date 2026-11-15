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
class TrafficSimChangeHandlerTest {
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
    fun `trafficSimChangeHandler resets baseline when lastDataSubId loading times out`() =
        runTest {
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(
                flow {
                    delay((Constants.Flow.LONG_TIMEOUT_MS + 1000).milliseconds)
                    emit(1)
                },
            )

            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_new")
            val target = TargetInfo(slot = 0, dataSubId = 2)

            val result = handler.handleSimChangeIfNeeded(target) { }

            assertNull("Should skip delta if previous subId is unknown", result)

            verify(baselineManager).resetBaseline(eq("sim_new"), any(), eq(true), any())
            verify(callSessionRepository).setLastDataSubId(2)
        }
}
