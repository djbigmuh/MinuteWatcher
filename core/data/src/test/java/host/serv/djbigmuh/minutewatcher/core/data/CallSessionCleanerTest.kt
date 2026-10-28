package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionCleanerTest {
    private val sessionRepository: CallSessionRepository = mock()
    private val attributionResolver: CallAttributionResolver = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 100_000_000L, currentRealtime = 50_000_000L)
    private val context: CallTrackingContext = mock()

    private lateinit var cleaner: CallSessionCleaner

    @Before
    fun setup() {
        initTestLogger()
        cleaner = CallSessionCleaner(sessionRepository, attributionResolver, timeProvider)
    }

    @Test
    fun `current boot session within tolerance is preserved`() =
        runTest {
            val subId = 1
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId))
            whenever(sessionRepository.getCallStartTime(subId)).thenReturn(flowOf(60_000_000L))
            whenever(sessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(10_000_000L))

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(0, cleared)
            verify(sessionRepository, never()).clearCallSession(any())
        }

    @Test
    fun `session with bootTime in future is cleared`() =
        runTest {
            val subId = 1
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId))
            whenever(sessionRepository.getCallStartTime(subId)).thenReturn(flowOf(110_000_000L))
            whenever(sessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(60_000_000L))

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(1, cleared)
            verify(sessionRepository).clearCallSession(subId)
            verify(attributionResolver).clearFailures(subId)
        }

    @Test
    fun `session with epoch drift exceeding tolerance is cleared`() =
        runTest {
            val subId = 1
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId))
            whenever(sessionRepository.getCallStartTime(subId)).thenReturn(flowOf(10_000_000L))
            whenever(sessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(10_000_000L))

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(1, cleared)
            verify(sessionRepository).clearCallSession(subId)
        }

    @Test
    fun `session with drift within tolerance is preserved`() =
        runTest {
            val subId = 1
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId))
            whenever(sessionRepository.getCallStartTime(subId)).thenReturn(flowOf(60_005_000L))
            whenever(sessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(10_000_000L))

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(0, cleared)
            verify(sessionRepository, never()).clearCallSession(any())
        }

    @Test
    fun `incomplete record is cleared`() =
        runTest {
            val subId = 1
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId))
            whenever(sessionRepository.getCallStartTime(subId)).thenReturn(flowOf(0L))
            whenever(sessionRepository.getElapsedRealtime(subId)).thenReturn(flowOf(10_000_000L))

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(1, cleared)
            verify(sessionRepository).clearCallSession(subId)
        }

    @Test
    fun `timeout or error reading session data preserves session and continues`() =
        runTest {
            val subId1 = 1
            val subId2 = 2
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId1, subId2))
            whenever(sessionRepository.getCallStartTime(subId1)).thenAnswer { throw IOException("Read error") }
            whenever(sessionRepository.getCallStartTime(subId2)).thenReturn(flowOf(60_000_000L))
            whenever(sessionRepository.getElapsedRealtime(subId2)).thenReturn(flowOf(10_000_000L))

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(0, cleared)
            verify(sessionRepository, never()).clearCallSession(any())
        }

    @Test
    fun `error reading active sessions returns zero and updates status with TIMEOUT`() =
        runTest {
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(null)

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(0, cleared)
            verify(context).updateStatus(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.UNKNOWN,
                    "Active call subIds read timed out or failed",
                    TrackingStatus.ErrorCode.TIMEOUT,
                ),
            )
        }

    @Test
    fun `error clearing one session updates status with DATABASE_ERROR and continues others`() =
        runTest {
            val subId1 = 1
            val subId2 = 2
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(listOf(subId1, subId2))
            whenever(sessionRepository.getCallStartTime(subId1)).thenReturn(flowOf(110_000_000L))
            whenever(sessionRepository.getElapsedRealtime(subId1)).thenReturn(flowOf(60_000_000L))
            whenever(sessionRepository.getCallStartTime(subId2)).thenReturn(flowOf(110_000_000L))
            whenever(sessionRepository.getElapsedRealtime(subId2)).thenReturn(flowOf(60_000_000L))

            whenever(sessionRepository.clearCallSession(subId1)).thenAnswer { throw IOException("DB error") }

            val cleared = cleaner.clearStaleSessions(context)

            assertEquals(1, cleared)
            verify(sessionRepository).clearCallSession(subId1)
            verify(sessionRepository).clearCallSession(subId2)
            verify(context).updateStatus(
                eq(
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Failed to clear session for subId $subId1: DB error",
                    ),
                ),
                any(),
            )
        }
}
