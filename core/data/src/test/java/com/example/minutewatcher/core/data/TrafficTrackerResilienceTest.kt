package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerResilienceTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val errorHandler: TrafficErrorHandler = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()

    @Before
    fun setup() {
        initTestLogger()
        // Default permission success
        whenever(permissionChecker.checkPermissions(any())).thenReturn(true)
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
    }

    private fun createTracker(scope: TestScope): TrafficTracker =
        TrafficTracker(
            simIdentityRepository = simIdentityRepository,
            simUsageRepository = simUsageRepository,
            simUsageResetRepository = simUsageResetRepository,
            permissionManager = permissionManager,
            baselineManager = baselineManager,
            deltaCalculator = deltaCalculator,
            simChangeHandler = simChangeHandler,
            targetResolver = targetResolver,
            errorHandler = errorHandler,
            permissionChecker = permissionChecker,
            permissionStateNotifier = permissionStateNotifier,
            dataChangeNotifier = mock(),
            scope = scope.backgroundScope,
        )

    @Test
    fun `initialization retries up to 3 times on general exceptions`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                error("General error")
            }

            val tracker = createTracker(this)
            runCurrent()

            // 1 from observer, 1 from init (Attempt 1)
            verify(simIdentityRepository, atLeast(2)).observeActiveSims().let {}

            advanceTimeBy(1001.milliseconds)
            runCurrent()
            // Attempt 2
            verify(simIdentityRepository, atLeast(3)).observeActiveSims().let {}

            advanceTimeBy(2001.milliseconds)
            runCurrent()
            // Attempt 3
            verify(simIdentityRepository, atLeast(4)).observeActiveSims().let {}

            advanceTimeBy(4001.milliseconds)
            runCurrent()
            // No more attempts (from init coroutine)
            verify(simIdentityRepository, times(4)).observeActiveSims().let {}
            verify(permissionStateNotifier, atLeast(1)).changes.let {}
        }

    @Test
    fun `initialization stops retrying immediately on SecurityException`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer { throw SecurityException("No permission") }

            val tracker = createTracker(this)
            runCurrent()

            // 1 from observer, 1 from init
            verify(simIdentityRepository, atLeast(2)).observeActiveSims().let {}

            advanceTimeBy(10.seconds)
            runCurrent()
            verify(simIdentityRepository, times(2)).observeActiveSims().let {}
            verify(permissionStateNotifier, atLeast(1)).changes.let {}
        }

    @Test
    fun `initialization stops retrying immediately on timeout`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer { throw TrafficUpdateTimeoutException() }

            val tracker = createTracker(this)
            runCurrent()

            // 1 from observer, 1 from init
            verify(simIdentityRepository, atLeast(2)).observeActiveSims().let {}

            advanceTimeBy(10.seconds)
            runCurrent()
            verify(simIdentityRepository, times(2)).observeActiveSims().let {}
            verify(permissionStateNotifier, atLeast(1)).changes.let {}
        }

    @Test
    fun `recover triggers new initialization attempt with reset retry logic`() =
        runTest {
            var callCount = 0
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                callCount++
                // Call 1: observer
                // Calls 2-4: init retries
                // Call 5+: success (from recover)
                if (callCount <= 4) error("Fail")
                flowOf(emptyList<ActiveSimInfo>())
            }

            val tracker = createTracker(this)
            runCurrent()

            // Let init retries finish (calls 2, 3, 4)
            advanceTimeBy(4000.milliseconds)
            runCurrent()
            verify(simIdentityRepository, times(4)).observeActiveSims().let {}

            // Act: Recover should try again and succeed (5th call)
            tracker.recover()
            runCurrent()

            // Total calls: 1 (observer) + 3 (init) + 1 (recover) = 5
            verify(simIdentityRepository, times(5)).observeActiveSims().let {}
            verify(permissionStateNotifier, atLeast(1)).changes.let {}
            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }
}
