package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.common.util.pollUntil
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

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

            // 1. Wait boundedly for background coroutines to start, execute initial observer setup, and stabilize
            pollUntil(
                timeoutMs = 5000L,
                message = "Observers initialized, active, and invoked initial calls after ensureStarted",
            ) {
                simCallCount.get() >= 1 &&
                    changesAccessCount.get() >= 1 &&
                    tracker.permissionObserverJob.get()?.isActive == true &&
                    tracker.simObserverJob.get()?.isActive == true
            }

            assertEquals(true, tracker.permissionObserverJob.get()?.isActive)
            assertEquals(true, tracker.simObserverJob.get()?.isActive)

            // 2. Capture baseline counts after stabilization
            val baselineSimCalls = simCallCount.get()
            val baselineChangesAccess = changesAccessCount.get()

            // 3. Cancel both jobs and wait for cancellation
            tracker.permissionObserverJob.get()?.cancel()
            tracker.simObserverJob.get()?.cancel()
            pollUntil(
                timeoutMs = 5000L,
                message = "Observer jobs canceled",
            ) {
                tracker.permissionObserverJob.get()?.isActive != true &&
                    tracker.simObserverJob.get()?.isActive != true
            }

            assertEquals(false, tracker.permissionObserverJob.get()?.isActive)
            assertEquals(false, tracker.simObserverJob.get()?.isActive)

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

            pollUntil(
                timeoutMs = 5000L,
                message = "New observers active after concurrent ensureStarted",
            ) {
                val newSimCalls = simCallCount.get() - baselineSimCalls
                val newChangesAccess = changesAccessCount.get() - baselineChangesAccess
                newSimCalls == 1 &&
                    newChangesAccess == 1 &&
                    tracker.permissionObserverJob.get()?.isActive == true &&
                    tracker.simObserverJob.get()?.isActive == true
            }

            // 5. Verify exact counts and active state
            val newSimCalls = simCallCount.get() - baselineSimCalls
            val newChangesAccess = changesAccessCount.get() - baselineChangesAccess

            assertEquals("Exact one new sim observer start", 1, newSimCalls)
            assertEquals("Exact one new permission observer start", 1, newChangesAccess)

            assertEquals(true, tracker.permissionObserverJob.get()?.isActive)
            assertEquals(true, tracker.simObserverJob.get()?.isActive)
        }
}
