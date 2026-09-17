package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
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
    fun `onBootAwaited clears sessions synchronously`() =
        runTest(testDispatcher) {
            callTracker.onBootAwaited()

            verify(sessionCleaner).clearAllSessions()

            // Wait for refreshStatus inside finally
            advanceUntilIdle()

            assertEquals(TrackingStatus.Idle, callTracker.status.value)
        }
}
