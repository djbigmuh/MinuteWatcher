package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class CallTrackerBootTest {
    private val sessionRepository: CallSessionRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val sessionCleaner: CallSessionCleaner = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var callTracker: CallTracker

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        runTest {
            whenever(sessionRepository.getActiveCallSubIds()).thenReturn(emptyList())
        }

        callTracker =
            CallTracker(
                sessionRepository,
                permissionManager,
                testScope,
                mock(), // attributionResolver
                mock(), // startHandler
                mock(), // endHandler
                sessionCleaner,
                permissionStateNotifier,
                CallMutexHolder(),
            )
    }

    @Test
    fun `onBootAwaited performs selective cleanup via clearStaleSessions`() =
        runTest(testDispatcher) {
            whenever(sessionCleaner.clearStaleSessions(any())).thenReturn(1)

            callTracker.onBootAwaited()

            verify(sessionCleaner).clearStaleSessions(any())
            verify(sessionCleaner, never()).clearAllSessions()

            // Wait for refreshStatus inside finally
            advanceUntilIdle()

            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }

    @Test
    fun `ensureStarted called repeatedly replaces permission observer cleanly`() =
        runTest(testDispatcher) {
            callTracker.ensureStarted()
            advanceUntilIdle()

            callTracker.ensureStarted()
            advanceUntilIdle()

            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }
}
