package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.data.util.DataStoreRetryHelper
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerCancellationRegressionTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider: TimeProvider = mock()
    private val networkStatsProvider: NetworkStatsProvider = mock()
    private val callSessionRepository: CallSessionRepository = mock()

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())
    }

    @Test
    fun `TrafficTracker startInitialization does NOT proceed if cancelled during join`() =
        runTest {
            val tracker = createTracker(backgroundScope)

            // 1. Create a job that will stay in 'Cancelling' state for a bit
            val oldJob =
                backgroundScope.launch {
                    withContext(NonCancellable) {
                        delay(1000)
                    }
                }
            tracker.initJob.set(oldJob)

            var startInitializationFinished = false
            val job =
                launch {
                    try {
                        tracker.startInitialization()
                        startInitializationFinished = true
                    } catch (
                        // @Suppress("SwallowedException") is required because test verifies
                        // expected exception handling without rethrowing.
                        @Suppress("SwallowedException")
                        e: Exception,
                    ) {
                        // expected
                    }
                }

            runCurrent()
            // Now job should be suspended at oldJob.join()

            job.cancel()
            runCurrent()

            // Even if oldJob completes now, 'job' should have been cancelled at ensureActive()
            testScheduler.advanceTimeBy(2000)
            runCurrent()

            assertFalse(
                "startInitialization should have been cancelled and NOT finished",
                startInitializationFinished,
            )
        }

    @Test
    fun `CallEndHandler finalizeCallEnd does NOT rethrow CancellationException`() =
        runTest {
            val handler =
                CallEndHandler(
                    callSessionRepository,
                    timeProvider,
                    mock(),
                    mock(),
                    CallMutexHolder(),
                    mock(),
                    mock(),
                    mock(),
                    dataChangeNotifier,
                )

            val context: CallTrackingContext = mock()
            whenever(context.refreshStatus(any()))
                .thenThrow(kotlinx.coroutines.CancellationException("Test"))

            // Should not throw
            handler.finalizeCallEnd(true, context)

            verify(context).refreshStatus(false)
        }

    private fun createTracker(scope: CoroutineScope): TrafficTracker {
        val baselineManager =
            TrafficBaselineManager(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                permissionManager,
                timeProvider,
                networkStatsProvider,
                dataChangeNotifier,
                DataStoreRetryHelper(),
            )
        val deltaCalculator = TrafficDeltaCalculator(simUsageResetRepository)
        val simChangeHandler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                baselineManager,
            )
        val targetResolver = TrafficTargetResolver(permissionManager)

        val deps =
            createTrafficTrackerDependencies(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                permissionManager,
                baselineManager,
                deltaCalculator,
                simChangeHandler,
                targetResolver,
                TrafficPermissionChecker(permissionManager),
                permissionStateNotifier,
                dataChangeNotifier,
                timeProvider,
            )
        return TrafficTracker(deps, scope).apply { ensureStarted() }
    }
}
