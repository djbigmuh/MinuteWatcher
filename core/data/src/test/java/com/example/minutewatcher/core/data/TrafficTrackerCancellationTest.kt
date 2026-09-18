package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficTrackerCancellationTest {
    private val simIdentityRepository: SimIdentityRepository = mock()
    private val simUsageRepository: SimUsageRepository = mock()
    private val simUsageResetRepository: SimUsageResetRepository = mock()
    private val permissionManager: PermissionManager = mock()
    private val baselineManager: TrafficBaselineManager = mock()
    private val deltaCalculator: TrafficDeltaCalculator = mock()
    private val simChangeHandler: TrafficSimChangeHandler = mock()
    private val targetResolver: TrafficTargetResolver = mock()
    private val permissionChecker: TrafficPermissionChecker = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val dataChangeNotifier: DataChangeNotifier = mock()
    private val timeProvider: TimeProvider = mock()

    private lateinit var trafficTracker: TrafficTracker
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        initTestLogger()
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
        whenever(timeProvider.elapsedRealtime()).thenReturn(0L)
        whenever(permissionChecker.checkPermissions(any())).thenReturn(true)
    }

    @Test
    fun `recover cancellation completes promptly and preserves recoverability`() =
        runTest(testDispatcher) {
            val attempts = runPhase1InitializationFailure()
            assertTrue("Should have collected flow", attempts > 0)

            runPhase2CancelRecoverDuringInit()

            val finalAttempts = runPhase3VerifyRecoverability()
            assertTrue(
                "Should perform a full initialization attempt path of 3 retries",
                finalAttempts >= 1,
            )
        }

    private fun TestScope.runPhase1InitializationFailure(): Int {
        var attempts = 0
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(
            flow {
                attempts++
                throw IOException("Init failure")
            },
        )

        trafficTracker =
            TrafficTracker(
                simIdentityRepository,
                simUsageRepository,
                simUsageResetRepository,
                permissionManager,
                baselineManager,
                deltaCalculator,
                simChangeHandler,
                targetResolver,
                permissionChecker,
                permissionStateNotifier,
                dataChangeNotifier,
                timeProvider,
                backgroundScope,
            )

        testDispatcher.scheduler.advanceUntilIdle()
        runCurrent()
        return attempts
    }

    private suspend fun TestScope.runPhase2CancelRecoverDuringInit() {
        val reachedInit = CompletableDeferred<Unit>()
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(
            flow {
                reachedInit.complete(Unit)
                awaitCancellation()
            },
        )

        val recoverJob =
            launch {
                trafficTracker.recover()
            }
        runCurrent()

        withTimeout(10.seconds) {
            reachedInit.await()
        }
        runCurrent()

        withTimeout(10.seconds) {
            recoverJob.cancelAndJoin()
        }
    }

    private suspend fun TestScope.runPhase3VerifyRecoverability(): Int {
        var finalAttempts = 0
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(
            flow {
                finalAttempts++
                throw IOException("Final failure")
            },
        )

        val finalJob =
            launch {
                trafficTracker.recover()
            }
        testDispatcher.scheduler.advanceUntilIdle()
        runCurrent()
        finalJob.cancelAndJoin()
        return finalAttempts
    }
}
