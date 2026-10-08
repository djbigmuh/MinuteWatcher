package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.util.DataStoreWriteTimeoutException
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
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
    fun `ensureInMemoryLastDataSubId retries on timeout`() =
        runTest {
            // 1. Setup repository to hang, causing a timeout
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(
                flow {
                    delay((Constants.Flow.LONG_TIMEOUT_MS + 1000).milliseconds)
                    emit(1)
                },
            )

            // 2. First call - should timeout
            handler.ensureInMemoryLastDataSubId()

            // Verify that we tried to load
            verify(callSessionRepository, times(1)).getLastDataSubId()

            // 3. Setup repository to return value immediately
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(
                flow {
                    emit(2)
                },
            )

            // 4. Second call - should retry because first one failed/timed out
            handler.ensureInMemoryLastDataSubId()

            // Verify that we tried to load AGAIN (total 2)
            verify(callSessionRepository, times(2)).getLastDataSubId()

            // 5. Third call - should NOT retry because second one succeeded
            handler.ensureInMemoryLastDataSubId()
            verify(callSessionRepository, times(2)).getLastDataSubId()
        }

    @Test
    fun `ensureInMemoryLastDataSubId retries on exception`() =
        runTest {
            // 1. Setup repository to throw exception
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(
                flow {
                    throw java.io.IOException("Database error")
                },
            )

            // 2. First call - should catch exception and not set loaded flag
            handler.ensureInMemoryLastDataSubId()

            verify(callSessionRepository, times(1)).getLastDataSubId()

            // 3. Setup repository to return value
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(
                flow {
                    emit(3)
                },
            )

            // 4. Second call - should retry
            handler.ensureInMemoryLastDataSubId()
            verify(callSessionRepository, times(2)).getLastDataSubId()

            // 5. Third call - should not retry
            handler.ensureInMemoryLastDataSubId()
            verify(callSessionRepository, times(2)).getLastDataSubId()
        }

    @Test
    fun `updateLastDataSubId with -1 does NOT set loaded flag`() =
        runTest {
            // 1. Initial state is not loaded
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flow { emit(5) })

            // 2. Call update with invalid value
            handler.updateLastDataSubId(-1)

            // 3. Flag should still be false, so ensureInMemoryLastDataSubId should still load from repository
            handler.ensureInMemoryLastDataSubId()

            verify(callSessionRepository, times(1)).getLastDataSubId()
        }

    @Test
    fun `SIM change detection works after updateLastDataSubId with -1`() =
        runTest {
            // 1. Persisted value is 5
            whenever(callSessionRepository.getLastDataSubId()).thenReturn(flow { emit(5) })

            // 2. Call update with -1 (failed to get default data SIM)
            handler.updateLastDataSubId(-1)

            // 3. New target SIM is 7. handleSimChangeIfNeeded should load 5 first.
            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_7")
            val target = TargetInfo(slot = 0, dataSubId = 7)

            val identity = handler.handleSimChangeIfNeeded(target) { }

            // 4. Identity should be null because SIM changed (5 -> 7)
            Assert.assertNull("Identity should be null on SIM change", identity)
            verify(baselineManager).resetBaseline(eq("sim_7"), any(), eq(true), any())
        }

    @Test
    fun `updateLastDataSubId retains in-memory value when setLastDataSubId throws DataStoreWriteTimeoutException`() =
        runTest {
            val timeoutException = DataStoreWriteTimeoutException("setLastDataSubId")
            whenever(callSessionRepository.setLastDataSubId(eq(10))).thenAnswer { throw timeoutException }

            handler.updateLastDataSubId(10)

            whenever(simIdentityRepository.getIdentity(0)).thenReturn("sim_10")
            val target = TargetInfo(slot = 0, dataSubId = 10)

            val identity = handler.handleSimChangeIfNeeded(target) { }
            Assert.assertEquals("sim_10", identity)
        }
}
