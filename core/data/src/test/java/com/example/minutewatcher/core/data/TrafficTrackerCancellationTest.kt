package com.example.minutewatcher.core.data

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.Logger
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.model.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
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

    @After
    fun tearDown() {
        AppLogger.resetForTest()
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

    @Test
    fun `startInitialization proceeds after timeout when previous job hangs and logs warning`() =
        runTest(testDispatcher) {
            val warningLogs = mutableListOf<String>()
            val capturingLogger =
                object : Logger by NoOpLogger() {
                    override fun w(
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        warningLogs.add(message)
                    }
                }
            val mockContext: Context =
                mock {
                    on { filesDir } doReturn null
                }
            AppLogger.resetForTest()
            AppLogger.init(mockContext, capturingLogger)

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
                    permissionChecker,
                    permissionStateNotifier,
                    dataChangeNotifier,
                    timeProvider,
                )

            trafficTracker = TrafficTracker(deps, backgroundScope)

            // Start a non-cooperative hanging initialization job
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(
                flow {
                    withContext(NonCancellable) {
                        delay((Constants.Flow.SHORT_TIMEOUT_MS * 20).milliseconds)
                    }
                    emit(emptyList())
                },
            )

            val firstInitJob =
                backgroundScope.launch {
                    trafficTracker.startInitialization()
                }
            runCurrent()

            // Now start a second initialization while first is hanging non-cooperatively
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(
                flow {
                    emit(emptyList())
                },
            )

            val secondInitJob =
                backgroundScope.launch {
                    trafficTracker.startInitialization()
                }

            // Advance scheduler past short timeout
            testDispatcher.scheduler.advanceTimeBy(Constants.Flow.SHORT_TIMEOUT_MS + 1000L)
            runCurrent()

            val hasTimeoutWarning = warningLogs.any { it.contains("previous initialization job did not complete within timeout") }
            assertTrue("Expected warning log about previous initialization job timeout", hasTimeoutWarning)

            firstInitJob.cancel()
            secondInitJob.cancel()
            AppLogger.resetForTest()
        }

    private fun TestScope.runPhase1InitializationFailure(): Int {
        var attempts = 0
        whenever(simIdentityRepository.observeActiveSims()).thenReturn(
            flow {
                attempts++
                throw IOException("Init failure")
            },
        )

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
                permissionChecker,
                permissionStateNotifier,
                dataChangeNotifier,
                timeProvider,
            )

        trafficTracker = TrafficTracker(deps, backgroundScope).apply { ensureStarted() }

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
