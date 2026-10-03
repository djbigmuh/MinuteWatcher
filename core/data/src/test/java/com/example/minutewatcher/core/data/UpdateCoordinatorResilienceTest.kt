package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
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

            // Advance time to cover 2 timeouts + delays (10s + 1s + 10s + 2s)
            testScheduler.advanceTimeBy(30.seconds)
            testScheduler.runCurrent()

            // Should eventually succeed and reach Active status
            assertEquals(TrackingStatus.Active, coordinator.status.value)
            // One call per attempt in loop (3) + one call for the main observation loop (1) = 4
            assertTrue("Call count should be at least 4, but was $callCount", callCount >= 4)
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

            // 3 attempts * 10s + delays
            testScheduler.advanceTimeBy(60.seconds)
            testScheduler.runCurrent()

            val status = coordinator.status.value
            assertTrue("Status should be Error after max retries, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
            assertEquals(3, callCount)
        }

    @Test
    fun `regression - recover resets state and starts new observation`() =
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

            // Wait for initial Active status
            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(TrackingStatus.Active, coordinator.status.value)

            coordinator.recover()

            // Wait for recovery startup
            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()

            assertEquals("Should be Active after recovery", TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `recover during active setup registers observation and monitoring restarts on failure`() =
        runTest {
            val supervisorJob = kotlinx.coroutines.SupervisorJob()
            val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val supervisorScope =
                kotlinx.coroutines.CoroutineScope(
                    testDispatcher + supervisorJob + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
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

            // Immediately recover while setup is pending
            coordinator.recover()

            // Emit initial snapshot so observation activates
            snapshotFlow.value = snapshot1
            runCurrent()
            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
            assertEquals(1, callCount)

            // Trigger terminal failure on second update
            snapshotFlow.value = snapshot2
            runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            runCurrent()

            assertEquals(2, callCount)
            assertTrue(coordinator.status.value is TrackingStatus.Error)

            // Wait for restart delay (5s)
            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()

            // Monitoring should restart observation and succeed on subsequent attempt
            assertEquals(3, callCount)
            assertEquals(TrackingStatus.Active, coordinator.status.value)

            supervisorJob.cancel()
        }

    @Test
    fun `non takeover cancellation causes monitoring restart`() =
        runTest {
            val supervisorJob = kotlinx.coroutines.SupervisorJob()
            val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val supervisorScope =
                kotlinx.coroutines.CoroutineScope(
                    testDispatcher + supervisorJob + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
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

            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(1, callCount)

            snapshotFlow.value = snapshot2
            runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            runCurrent()

            assertEquals(2, callCount)
            assertTrue(coordinator.status.value is TrackingStatus.Error)

            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()

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
                com.example.minutewatcher.core.model.SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = "Test",
                    entities =
                        mapOf(
                            com.example.minutewatcher.core.model.PackageType.MINUTES to
                                com.example.minutewatcher.core.model.SemanticEntity(
                                    id = "e1",
                                    simSlot = 0,
                                    operatorName = "Test",
                                    packageType = com.example.minutewatcher.core.model.PackageType.MINUTES,
                                    limit = 100,
                                    used = used,
                                    remaining = 100 - used,
                                    unit = com.example.minutewatcher.core.model.MeasurementUnit.MINUTES,
                                    periodStart = 0,
                                    periodEnd = 0,
                                    lastUpdateTime = timestamp,
                                    sourceType = com.example.minutewatcher.core.model.DataSourceType.SYSTEM,
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
            val simFlow = MutableStateFlow(listOf(mock<com.example.minutewatcher.core.model.SimCardUiModel>()))
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

            // Multiple recover calls before scheduler runs
            coordinator.recover()
            coordinator.recover()

            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
            assertTrue("SIM observe calls should be controlled without racing setup jobs", simObserveCalls <= 3)
        }
}
