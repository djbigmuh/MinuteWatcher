package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.FixedTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.Logger
import host.serv.djbigmuh.minutewatcher.core.common.util.NoOpLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.PermissionManager
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.common.util.resetAppLoggerForTest
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
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
    private val permissionChecker: TrafficPermissionChecker = mock()
    private val permissionStateNotifier: PermissionStateNotifier = mock()
    private val timeProvider = FixedTimeProvider()

    @Before
    fun setup() {
        initTestLogger()
        clearInvocations(
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
        )
        // Default permission success
        whenever(permissionChecker.checkPermissions(any())).thenReturn(true)
        whenever(permissionStateNotifier.changes).thenReturn(MutableSharedFlow())
    }

    @After
    fun tearDown() {
        resetAppLoggerForTest()
        reset(simIdentityRepository)
    }

    private fun createTracker(scope: TestScope): TrafficTracker {
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
                timeProvider = timeProvider,
            )
        return TrafficTracker(deps, scope.backgroundScope).apply { ensureStarted() }
    }

    @Test
    fun `initialization retries up to 3 times on general exceptions`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                error("General error")
            }

            val tracker = createTracker(this)
            runCurrent()

            // 1 from observer, 1 from init (Attempt 1)
            verify(simIdentityRepository, atLeast(2)).observeActiveSims()

            advanceTimeBy(1001.milliseconds)
            runCurrent()
            // Attempt 2
            verify(simIdentityRepository, atLeast(3)).observeActiveSims()

            advanceTimeBy(2001.milliseconds)
            runCurrent()
            // Attempt 3
            verify(simIdentityRepository, atLeast(4)).observeActiveSims()

            advanceTimeBy(4001.milliseconds)
            runCurrent()
            // No more attempts (from init coroutine)
            verify(simIdentityRepository, times(4)).observeActiveSims()
            verify(permissionStateNotifier, atLeast(1)).changes
        }

    @Test
    fun `initialization stops retrying immediately on SecurityException`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer { throw SecurityException("No permission") }

            val tracker = createTracker(this)
            runCurrent()

            // 1 from observer, 1 from init
            verify(simIdentityRepository, atLeast(2)).observeActiveSims()

            advanceTimeBy(10.seconds)
            runCurrent()
            verify(simIdentityRepository, times(2)).observeActiveSims()
            verify(permissionStateNotifier, atLeast(1)).changes
        }

    @Test
    fun `initialization stops retrying immediately on timeout`() =
        runTest {
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer { throw TrafficUpdateTimeoutException() }

            val tracker = createTracker(this)
            runCurrent()

            // 1 from observer, 1 from init
            verify(simIdentityRepository, atLeast(2)).observeActiveSims()

            advanceTimeBy(10.seconds)
            runCurrent()
            verify(simIdentityRepository, times(2)).observeActiveSims()
            verify(permissionStateNotifier, atLeast(1)).changes
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
            verify(simIdentityRepository, times(4)).observeActiveSims()

            // Act: Recover should try again and succeed
            tracker.recover()
            runCurrent()

            // Total calls: 1 (observer) + 3 (init) + 1 (observer restart in recover) + 1 (recover init) = 6
            verify(simIdentityRepository, times(6)).observeActiveSims()
            verify(permissionStateNotifier, atLeast(1)).changes
            assertEquals(TrackingStatus.Idle, tracker.status.value)
        }

    @Test
    fun `regression - fresh timeout error is NOT suppressed`() =
        runTest {
            val tracker = createTracker(this)

            // 1. Success at T=1000
            timeProvider.setRealtime(1000L)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            tracker.updateTrafficUsage() // This will set lastSuccessElapsed to 1000
            runCurrent()
            assertEquals(TrackingStatus.Idle, tracker.status.value)

            // 2. New update starts at T=2000, fails with timeout
            timeProvider.setRealtime(2000L)
            // Mocking timeout by making observeActiveSims never emit (or throw exception)
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                throw TrafficUpdateTimeoutException(startElapsed = 2000L)
            }

            tracker.updateTrafficUsage()
            runCurrent()

            // Status should be Error(TIMEOUT) because it's a fresh error (2000 > 1000)
            val status = tracker.status.value as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, status.code)
        }

    @Test
    fun `regression - stale timeout error IS suppressed by recent success`() =
        runTest {
            val tracker = createTracker(this)

            // 1. Update A starts at T=1000, will fail later

            // 2. Update B starts at T=1500 and succeeds at T=1600
            timeProvider.setRealtime(1600L)
            whenever(simIdentityRepository.observeActiveSims()).thenReturn(flowOf(emptyList()))
            tracker.updateTrafficUsage()
            runCurrent()
            assertEquals(TrackingStatus.Idle, tracker.status.value)
            // lastSuccessElapsed is now 1600

            // 3. Update A finally fails at T=2000 with its original start time T=1000
            timeProvider.setRealtime(2000L)
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                throw TrafficUpdateTimeoutException(startElapsed = 1000L)
            }

            tracker.updateTrafficUsage()
            runCurrent()

            // Status should NOT be Error because success (1600) > start (1000)
            // Note: in this test setup, it remains Idle (success of Update B)
            val currentStatus = tracker.status.value
            val lastSuccess = tracker.lastSuccessElapsed.get()
            assertEquals(
                "Status should be suppressed. Current status: $currentStatus, " +
                    "lastSuccessElapsed: $lastSuccess",
                TrackingStatus.Idle,
                currentStatus,
            )
        }

    @Test
    fun `exhausted initialization retries preserve and log last root cause`() =
        runTest {
            val errorLogs = mutableListOf<Pair<String, Throwable?>>()
            val capturingLogger =
                object : Logger by NoOpLogger() {
                    override fun e(
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        if (message.contains("initialization failed after 3 attempts")) {
                            errorLogs.add(message to throwable)
                        }
                    }
                }
            val mockContext: android.content.Context =
                mock {
                    on { filesDir } doReturn null
                }
            resetAppLoggerForTest()
            AppLogger.init(mockContext, capturingLogger)

            val exception1 = IOException("Attempt 1 failure")
            val exception2 = IllegalStateException("Attempt 2 failure")
            val exception3 = RuntimeException("Attempt 3 failure")

            var attempt = 0
            whenever(simIdentityRepository.observeActiveSims()).thenAnswer {
                attempt++
                when (attempt) {
                    1 -> throw exception1
                    2 -> throw exception2
                    3 -> throw exception3
                    else -> throw exception3
                }
            }

            val tracker = createTracker(this)
            runCurrent()

            advanceTimeBy(1000.milliseconds)
            runCurrent()
            advanceTimeBy(2000.milliseconds)
            runCurrent()
            advanceTimeBy(4000.milliseconds)
            runCurrent()

            val status = tracker.status.value as? TrackingStatus.Error
            assertNotNull(status)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, status?.type)

            val terminalLog = errorLogs.find { it.first.contains("initialization failed after 3 attempts") }
            assertNotNull(terminalLog)
            assertEquals(exception3.message, terminalLog?.second?.message)
            assertEquals(exception3::class, terminalLog?.second?.let { it::class })

            resetAppLoggerForTest()
        }

    @Test
    fun `active sims fetch timeout logs single error and no utility timeout log`() =
        runTest {
            val errorLogs = mutableListOf<Pair<String, Throwable?>>()
            val capturingLogger =
                object : Logger by NoOpLogger() {
                    override fun e(
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        errorLogs.add(message to throwable)
                    }

                    override fun w(
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        errorLogs.add(message to throwable)
                    }
                }
            val mockContext: android.content.Context =
                mock {
                    on { filesDir } doReturn null
                }
            resetAppLoggerForTest()
            AppLogger.init(mockContext, capturingLogger)

            whenever(simIdentityRepository.observeActiveSims()).thenReturn(MutableSharedFlow())

            val tracker = createTracker(this)
            runCurrent()

            advanceTimeBy((Constants.Flow.SHORT_TIMEOUT_MS + 1000L).milliseconds)
            runCurrent()

            val status = tracker.status.value as? TrackingStatus.Error
            assertNotNull(status)
            assertEquals(TrackingStatus.ErrorCode.TIMEOUT, status?.code)

            val singleErrorLog = errorLogs.find { it.first == "TrafficTracker: timeout fetching active sims" }
            assertNotNull(singleErrorLog)

            val utilityTimeoutLog =
                errorLogs.find {
                    it.first.contains("Timeout reading from Flow") ||
                        it.first.contains("activeSims")
                }
            assertTrue("Should not contain utility timeout log", utilityTimeoutLog == null)

            resetAppLoggerForTest()
        }
}
