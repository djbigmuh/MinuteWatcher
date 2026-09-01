package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficSimChangeHandlerTest {
    private val callSessionRepository: CallSessionRepository = mock()
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val baselineManager: TrafficBaselineManager = mock()

    private lateinit var handler: TrafficSimChangeHandler

    @Before
    fun setup() {
        initTestLogger()
        handler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                simUsageResetRepository,
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
}
