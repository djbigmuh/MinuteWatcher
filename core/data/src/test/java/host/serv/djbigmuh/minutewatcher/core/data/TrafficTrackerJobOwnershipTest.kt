package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerJobOwnershipTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager = mock()
    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider: TimeProvider = mock()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
        whenever(timeProvider.elapsedRealtime()).thenReturn(0L)
        whenever(permissionChecker.checkPermissions(any())).thenReturn(true)
    }

    private fun createTrackerDeps() =
        TrafficTrackerDependencies(
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
            dataChangeNotifier = dataChangeNotifier,
            timeProvider = timeProvider,
        )

    @Test
    fun `external caller coroutine calling recover is not cancelled when new initialization starts`() =
        runTest(testDispatcher) {
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            val deps = createTrackerDeps()
            val appScope = CoroutineScope(testDispatcher + Job())
            val tracker = TrafficTracker(deps, appScope)

            var callerCompletedPostRecoverStep = false

            val externalCallerJob =
                launch {
                    tracker.recover()
                    delay(10.milliseconds)
                    callerCompletedPostRecoverStep = true
                }

            runCurrent()

            tracker.startInitialization()

            advanceUntilIdle()

            assertTrue(
                "External caller coroutine should complete its post-recover work",
                callerCompletedPostRecoverStep,
            )
            assertTrue(
                "External caller job should be completed without cancellation",
                externalCallerJob.isCompleted && !externalCallerJob.isCancelled,
            )
            appScope.cancel()
        }

    @Test
    fun `cancellation while waiting on startInitialization cancels caller but leaves internal init job active`() =
        runTest(testDispatcher) {
            val initStarted = CompletableDeferred<Unit>()
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(
                flow {
                    initStarted.complete(Unit)
                    awaitCancellation()
                },
            )
            val deps = createTrackerDeps()
            val appScope = CoroutineScope(testDispatcher + Job())
            val tracker = TrafficTracker(deps, appScope)

            val callerJob =
                launch {
                    tracker.startInitialization()
                }

            runCurrent()
            initStarted.await()

            val internalJob = tracker.initJob.get()
            assertNotNull("Internal initJob should be set", internalJob)

            callerJob.cancelAndJoin()

            assertTrue("Caller job should be cancelled", callerJob.isCancelled)
            assertTrue("Internal initJob in appScope should remain active", internalJob!!.isActive)

            appScope.cancel()
        }
}
