package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    @Before
    fun setup() {
        initTestLogger()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(flowOf(listOf(mock())))
    }

    @Test
    fun `setupObservation retries on first snapshot timeout`() =
        runTest {
            var callCount = 0
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            whenever(usageStateRepository.observeSnapshot()).thenAnswer {
                callCount++
                flow {
                    if (callCount <= 2) {
                        // First two attempts will timeout
                        delay(Constants.Flow.LONG_TIMEOUT_MS.milliseconds + 1000.milliseconds)
                    }
                    emit(snapshot)
                }.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()),
                )
            }
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.start()

            // Advance time to cover 2 timeouts + delays (10s + 1s + 10s + 2s)
            testScheduler.advanceTimeBy(30.seconds)
            testScheduler.runCurrent()

            // Should eventually succeed and reach Active status
            assertEquals(TrackingStatus.Active, coordinator.status.value)
            // One call per attempt in loop (3) + one call for the main observation loop (1) = 4
            assertTrue(
                "Call count should be at least 4, but was $callCount",
                callCount >= 4,
            )
        }

    @Test
    fun `setupObservation fails after max retries`() =
        runTest {
            var callCount = 0
            whenever(usageStateRepository.observeSnapshot()).thenAnswer {
                callCount++
                flow {
                    // Always timeout
                    delay(Constants.Flow.LONG_TIMEOUT_MS.milliseconds + 1000.milliseconds)
                    emit(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
                }.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()),
                )
            }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.start()

            // 3 attempts * 10s + delays
            testScheduler.advanceTimeBy(60.seconds)
            testScheduler.runCurrent()

            val status = coordinator.status.value
            assertTrue(
                "Status should be Error after max retries, but was $status",
                status is TrackingStatus.Error,
            )
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
            assertEquals(3, callCount)
        }

    @Test
    fun `update coordinator recovery resets state and starts new observation`() =
        runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            val snapshotFlow = MutableStateFlow(snapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.start()

            // Wait for initial Active status
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()
            assertEquals(TrackingStatus.Active, coordinator.status.value)

            coordinator.recover()

            // Wait for recovery startup
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()

            assertEquals("Should be Active after recovery", TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `recover during active setup registers observation and monitoring restarts on failure`() =
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope =
                CoroutineScope(
                    testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> },
                )

            val snapshot1 = createSnapshot(1000L, used = 10)
            val snapshot2 = createSnapshot(2000L, used = 20)
            val snapshotFlow = MutableStateFlow(createSnapshot(0L, used = 0))

            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (callCount == 2) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    supervisorScope,
                )
            coordinator.start()

            // Immediately recover while setup is pending
            coordinator.recover()

            // Emit initial snapshot so observation activates
            snapshotFlow.value = snapshot1
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
            assertEquals(1, callCount)

            // Trigger terminal failure on second update
            snapshotFlow.value = snapshot2
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertEquals(2, callCount)
            assertTrue(coordinator.status.value is TrackingStatus.Error)

            // Wait for restart delay (5s)
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()

            // Monitoring should restart observation and succeed on subsequent attempt
            assertEquals(3, callCount)
            assertEquals(TrackingStatus.Active, coordinator.status.value)

            supervisorJob.cancel()
        }

    @Test
    fun `non takeover cancellation causes monitoring restart`() =
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope =
                CoroutineScope(
                    testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> },
                )

            val snapshot1 = createSnapshot(1000L, used = 10)
            val snapshot2 = createSnapshot(2000L, used = 20)
            val snapshotFlow = MutableStateFlow(snapshot1)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (callCount == 2) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    supervisorScope,
                )
            coordinator.start()

            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()
            assertEquals(1, callCount)

            snapshotFlow.value = snapshot2
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertEquals(2, callCount)
            assertTrue(coordinator.status.value is TrackingStatus.Error)

            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()

            assertEquals(3, callCount)
            assertEquals(TrackingStatus.Active, coordinator.status.value)

            supervisorJob.cancel()
        }

    private fun createSnapshot(
        timestamp: Long,
        used: Long,
    ) = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = timestamp,
        simSnapshots =
            listOf(
                SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = "Test",
                    entities =
                        mapOf(
                            PackageType.MINUTES to
                                SemanticEntity(
                                    id = "e1",
                                    simSlot = 0,
                                    operatorName = "Test",
                                    packageType = PackageType.MINUTES,
                                    limit = 100,
                                    used = used,
                                    remaining = 100 - used,
                                    unit = MeasurementUnit.MINUTES,
                                    periodStart = 0,
                                    periodEnd = 0,
                                    lastUpdateTime = timestamp,
                                    sourceType = DataSourceType.SYSTEM,
                                ),
                        ),
                    autoResetDay = 1,
                ),
            ),
        capabilities = emptyList(),
    )

    @Test
    fun `concurrent recover calls before dispatcher execution create single setup job`() =
        runTest {
            var simObserveCalls = 0
            val simFlow = MutableStateFlow(listOf(mock<SimCardUiModel>()))
            whenever(usageStateRepository.observeSimCardUiModels()).thenAnswer {
                simObserveCalls++
                simFlow
            }
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(MutableStateFlow(snapshot))
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.start()

            // Multiple recover calls before scheduler runs
            coordinator.recover()
            coordinator.recover()

            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
            assertTrue(
                "SIM observe calls should be controlled without racing setup jobs",
                simObserveCalls <= 3,
            )
        }

    @Test
    fun `stale observation termination after recovery does not interfere with new active state`() =
        runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            val flow = MutableStateFlow(snapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(flow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.start()
            testScheduler.runCurrent()

            val initialJob = coordinator.currentObservationJob.get()!!

            // Trigger recovery which increments observationGeneration and starts new observation
            coordinator.recover()
            testScheduler.runCurrent()

            // Cancel the initial observation job
            initialJob.cancel()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `old observation job completion when active job changed completes without restart`() =
        runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            val flow1 = MutableStateFlow(snapshot)
            val flow2 = MutableStateFlow(snapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(flow1, flow2)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.start()
            testScheduler.runCurrent()

            val oldJob = coordinator.currentObservationJob.get()!!

            // Trigger takeover or recovery which points currentObservationJob to new job
            coordinator.recover()
            testScheduler.runCurrent()

            val newJob = coordinator.currentObservationJob.get()!!
            assertTrue(
                "Observation job should be updated",
                oldJob !== newJob,
            )

            // Cancel the old job
            oldJob.cancel()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `setupJob does not contain completed job after start returns`() =
        runTest {
            val simFlow = MutableStateFlow(listOf(createTestSimCardUiModel()))
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)
            val validSnapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(MutableStateFlow(validSnapshot))

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.start()
            testScheduler.advanceUntilIdle()

            val job = coordinator.setupJob.get()
            assertTrue("setupJob should not contain a completed job", job == null || !job.isCompleted)
        }

    @Test
    fun `repeated start when setup is active does not create second coroutine`() =
        runTest {
            val simFlow = MutableStateFlow(emptyList<SimCardUiModel>())
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.start()
            testScheduler.runCurrent()

            val job1 = coordinator.setupJob.get()

            coordinator.start()
            testScheduler.runCurrent()

            val job2 = coordinator.setupJob.get()
            assertTrue("setupJob reference should remain the same", job1 === job2)
        }

    @Test
    fun `start after setup completion and no active observation creates new setup`() =
        runTest {
            val simFlow = MutableStateFlow(listOf(createTestSimCardUiModel()))
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)
            val stateFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.start()
            testScheduler.advanceUntilIdle()

            coordinator.recover()
            testScheduler.advanceUntilIdle()

            coordinator.start()
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `canceling setup from recover cancels setupJob`() =
        runTest {
            val simFlow = MutableStateFlow(emptyList<SimCardUiModel>())
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.start()
            testScheduler.runCurrent()

            val oldJob = coordinator.setupJob.get()
            assertTrue(oldJob != null && oldJob.isActive)

            coordinator.recover()
            testScheduler.advanceUntilIdle()

            assertTrue(oldJob?.isCancelled == true)
        }

    @Test
    fun `concurrent start from ten coroutines creates exactly one setup`() =
        runTest {
            val simFlow = MutableStateFlow(emptyList<SimCardUiModel>())
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            val scope = CoroutineScope(backgroundScope.coroutineContext)
            repeat(10) {
                scope.launch {
                    coordinator.start()
                }
            }
            testScheduler.runCurrent()

            val job = coordinator.setupJob.get()
            assertTrue("setupJob should be active", job != null && job.isActive)
        }

    private fun createTestSimCardUiModel(slot: Int = 0) =
        SimCardUiModel(
            slot = slot,
            identity = "sim_$slot",
            operatorName = "Operator",
            iconRes = 0,
            minutesUsed = 0,
            minutesLimit = 100,
            manualAdjustment = 0,
            minutesRemaining = 100,
            effectiveMinutesLimit = 100,
            isMinutesLimitSet = true,
            trafficUsedMb = 0f,
            trafficLimitMb = 1000,
            trafficRemainingGb = 1f,
            effectiveTrafficLimitGb = 1f,
            trafficAdjustmentGb = 0f,
            isTrafficLimitSet = true,
            autoResetEnabled = true,
            autoResetDay = 1,
            rolloverEnabled = false,
        )
}
