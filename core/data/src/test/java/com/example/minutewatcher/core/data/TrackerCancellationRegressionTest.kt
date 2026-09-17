package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

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

            val field =
                TrafficTracker::class.java.getDeclaredField("initJob").apply {
                    isAccessible = true
                }
            val atomicRef = field.get(tracker) as AtomicReference<Job?>

            // Create a job that will stay in 'Cancelling' state for a bit
            val oldJob =
                backgroundScope.launch {
                    withContext(NonCancellable) {
                        delay(1000)
                    }
                }
            atomicRef.set(oldJob)

            val method =
                TrafficTracker::class.java.getDeclaredMethod(
                    "startInitialization",
                    Continuation::class.java,
                )
            method.isAccessible = true

            var startInitializationFinished = false
            val job =
                launch {
                    try {
                        suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                            method.invoke(
                                tracker,
                                cont,
                            )
                        }
                        startInitializationFinished = true
                    } catch (
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

            val method =
                CallEndHandler::class.java.getDeclaredMethod(
                    "finalizeCallEnd",
                    Boolean::class.javaPrimitiveType,
                    CallTrackingContext::class.java,
                    Continuation::class.java,
                )
            method.isAccessible = true

            // Should not throw
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                method.invoke(
                    handler,
                    true,
                    context,
                    cont,
                )
            }

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
            )
        val deltaCalculator = TrafficDeltaCalculator(simUsageResetRepository)
        val simChangeHandler =
            TrafficSimChangeHandler(
                callSessionRepository,
                simIdentityRepository,
                baselineManager,
            )
        val targetResolver = TrafficTargetResolver(permissionManager)

        return TrafficTracker(
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
            scope,
        )
    }
}
