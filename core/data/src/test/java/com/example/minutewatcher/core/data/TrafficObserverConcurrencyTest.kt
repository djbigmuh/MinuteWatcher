package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.ActiveSimInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficObserverConcurrencyTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()

    private val permissionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val simFlow = MutableSharedFlow<List<ActiveSimInfo>>(extraBufferCapacity = 1)

    private val simCallCount = AtomicInteger(0)
    private val changesAccessCount = AtomicInteger(0)

    private val permissionStateNotifier =
        object : PermissionStateNotifier {
            override val changes: Flow<Unit>
                get() {
                    changesAccessCount.incrementAndGet()
                    return permissionChanges
                }

            override fun notifyChanged() {
                // Not used in test
            }
        }

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionChecker.checkPermissions(any())).thenReturn(true)
        whenever(permissionManager.hasPhoneStatePermission()).thenReturn(true)
        whenever(permissionManager.hasUsageStatsPermission()).thenReturn(true)

        whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
            simCallCount.incrementAndGet()
            simFlow
        }
    }

    @Test
    fun `observers have unique active instances after concurrent restarts`() =
        runTest {
            val deps =
                createTrafficTrackerDependencies(
                    simIdentityRepository = simIdentityRepository,
                    simUsageRepository = simUsageRepository,
                    simUsageResetRepository = simUsageResetRepository,
                    permissionManager = permissionManager,
                    baselineManager = baselineManager,
                    deltaCalculator = deltaCalculator,
                    simChangeHandler = simChangeHandler,
                    targetResolver = targetResolver,
                    permissionChecker = permissionChecker,
                    permissionStateNotifier = permissionStateNotifier,
                    dataChangeNotifier = mock(),
                    timeProvider = FixedTimeProvider(),
                )

            val tracker = TrafficTracker(deps, CoroutineScope(Dispatchers.Default + SupervisorJob()))

            // 1. Initial start
            tracker.ensureStarted()

            // Wait a moment for background coroutines to start
            while (tracker.permissionObserverJob.get() == null || tracker.simObserverJob.get() == null) {
                Thread.sleep(10)
            }
            Thread.sleep(50) // allow stabilization

            assertTrue(tracker.permissionObserverJob.get()?.isActive == true)
            assertTrue(tracker.simObserverJob.get()?.isActive == true)

            // 2. Capture baseline counts after stabilization
            val baselineSimCalls = simCallCount.get()
            val baselineChangesAccess = changesAccessCount.get()

            // 3. Cancel both jobs
            tracker.permissionObserverJob.get()?.cancel()
            tracker.simObserverJob.get()?.cancel()
            Thread.sleep(50) // allow cancellation to take effect

            assertTrue(tracker.permissionObserverJob.get()?.isActive != true)
            assertTrue(tracker.simObserverJob.get()?.isActive != true)

            // 4. Concurrent calls from 4 threads using CyclicBarrier
            val threadCount = 4
            val barrier = CyclicBarrier(threadCount)
            val threads =
                List(threadCount) {
                    Thread {
                        barrier.await()
                        tracker.ensureStarted()
                    }
                }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            Thread.sleep(100) // allow new observers to stabilize

            // 5. Verify exact counts and active state
            val newSimCalls = simCallCount.get() - baselineSimCalls
            val newChangesAccess = changesAccessCount.get() - baselineChangesAccess

            assertEquals("Exact one new sim observer start", 1, newSimCalls)
            assertEquals("Exact one new permission observer start", 1, newChangesAccess)

            assertTrue(tracker.permissionObserverJob.get()?.isActive == true)
            assertTrue(tracker.simObserverJob.get()?.isActive == true)
        }
}
