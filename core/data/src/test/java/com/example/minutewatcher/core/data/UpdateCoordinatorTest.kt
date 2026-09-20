package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("MaxLineLength", "LargeClass")
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateCoordinatorTest {
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private val snapshotFlow = MutableSharedFlow<GlobalSnapshot>(replay = 1, extraBufferCapacity = 10)

    @Before
    fun setup() {
        runTest {
            initTestLogger()
            snapshotFlow.tryEmit(createEmptySnapshot())
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            val simFlow = MutableStateFlow(listOf(mock<SimCardUiModel>()))
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)
        }
    }

    @Test
    fun `snapshot change triggers widget update with debounce`() {
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.status
            testScheduler.runCurrent()

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.emit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `terminal failure triggers restart on same snapshot`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            val stateFlow = snapshotFlow.stateIn(supervisorScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (callCount == 1) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, supervisorScope)
            coordinator.status
            testScheduler.runCurrent()

            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "RestartTest"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertEquals("First attempt failed", 1, callCount)
            assertTrue("Status should be Error after failure", coordinator.status.value is TrackingStatus.Error)

            // 2. Wait for INITIAL_RESTART_DELAY_MS (5s)
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.advanceUntilIdle()

            assertEquals("Should have restarted and called updateAll again", 2, callCount)
            assertEquals(
                "Status should be Active after successful restart",
                TrackingStatus.Active,
                coordinator.status.value,
            )

            supervisorJob.cancel()
        }
    }

    @Test
    fun `observation enters degraded mode after 5 consecutive crashes`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            val stateFlow = snapshotFlow.stateIn(supervisorScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, supervisorScope)
            coordinator.status
            testScheduler.runCurrent()

            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "PersistentFail"))

            // 5 calls: 1 initial + 4 restarts
            // Delays: 5s, 10s, 20s, 40s. Total 75s.
            testScheduler.advanceTimeBy(100.seconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(5)).updateAll()

            val status = coordinator.status.value as TrackingStatus.Error
            assertTrue(
                "Should contain 'Retrying in', got: ${status.message}",
                status.message?.contains("Retrying in") == true,
            )
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, status.type)

            supervisorJob.cancel()
        }
    }

    @Test
    fun `observation stops permanently after 3 degraded cycles`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            val stateFlow = snapshotFlow.stateIn(supervisorScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, supervisorScope)
            coordinator.status
            testScheduler.runCurrent()

            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "InfiniteFail"))

            // Cycle 1: 5 attempts + 300s
            // Cycle 2: 5 attempts + 300s
            // Cycle 3: 5 attempts + Stop
            testScheduler.advanceTimeBy(2000.seconds)
            testScheduler.runCurrent()

            val status = coordinator.status.value as TrackingStatus.Error
            assertTrue(
                "Should contain 'permanently stopped', got: ${status.message}",
                status.message?.contains("permanently stopped") == true,
            )

            verify(widgetUpdater, times(15)).updateAll()

            supervisorJob.cancel()
        }
    }

    @Test
    fun `successful updates reset observation restart counter`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            val stateFlow = snapshotFlow.stateIn(supervisorScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            var failNext = true
            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (failNext) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, supervisorScope)
            coordinator.status
            testScheduler.runCurrent()

            // 1. Fail 4 times (Initial + 3 restarts)
            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "ResetTest"))
            testScheduler.advanceTimeBy(50.seconds) // Initial(0.3) + 5 + 10 + 20
            testScheduler.runCurrent()
            assertEquals(4, callCount)

            // 2. Succeed on next attempt (4th restart)
            failNext = false
            testScheduler.advanceTimeBy(40.seconds) // 4th restart delay
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1.seconds) // debounce
            testScheduler.runCurrent()
            assertEquals("Status should be Active", TrackingStatus.Active, coordinator.status.value)
            assertEquals(5, callCount)

            // 3. Fail again - should restart with INITIAL delay (5s)
            failNext = true
            snapshotFlow.emit(createSnapshot(timestamp = 2000L, content = "FailAgain"))
            testScheduler.advanceTimeBy(1.seconds) // debounce
            testScheduler.runCurrent()
            assertEquals(6, callCount)
            assertTrue(coordinator.status.value is TrackingStatus.Error)

            // Wait 5s - should restart
            testScheduler.advanceTimeBy(5.seconds)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1.seconds) // debounce
            testScheduler.runCurrent()
            assertEquals("Should have restarted after 5s", 7, callCount)

            supervisorJob.cancel()
        }
    }

    @Test
    fun `manual update triggers immediate update without debounce`() {
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.status
            testScheduler.runCurrent()
            val s1 = createSnapshot(timestamp = 1000L, content = "Manual", isManual = true)
            snapshotFlow.emit(s1)
            testScheduler.advanceTimeBy(10.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `observation ignores duplicate snapshot content`() {
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.status
            testScheduler.runCurrent()
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.emit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(1)).updateAll()
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.emit(s2)
            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()
            verify(widgetUpdater, times(2)).updateAll()
            val s2Suppressed = s2.copy(suppressWidgetUpdate = true)
            snapshotFlow.emit(s2Suppressed)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(2)).updateAll()
        }
    }

    @Test
    fun `regression - recover resets all internal states and sticky Error status`() {
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Failure(wasCoalesced = false))

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.status
            testScheduler.runCurrent()

            for (i in 1..5) {
                snapshotFlow.emit(createSnapshot(timestamp = i * 1000L, content = "Fail $i"))
                testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
            }

            assertTrue(coordinator.status.value is TrackingStatus.Error)

            coordinator.recover()
            assertEquals(
                "Status should be Idle immediately after recover",
                TrackingStatus.Idle,
                coordinator.status.value,
            )

            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            snapshotFlow.emit(createSnapshot(timestamp = 7000L, content = "Fail 5"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, atLeastOnce()).updateAll()
            assertEquals("Status should eventually become Active", TrackingStatus.Active, coordinator.status.value)
        }
    }

    private fun createEmptySnapshot() =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 0L,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )

    private fun createSnapshot(
        timestamp: Long,
        content: String,
        suppressUpdate: Boolean = false,
        isManual: Boolean = false,
    ) = GlobalSnapshot(
        version = SchemaVersion(1, 0, content.hashCode()),
        timestamp = timestamp,
        isManualUpdate = isManual,
        suppressWidgetUpdate = suppressUpdate,
        simSnapshots =
            listOf(
                SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = "MTS",
                    autoResetDay = 1,
                    entities =
                        mapOf(
                            PackageType.MINUTES to
                                SemanticEntity(
                                    id = content,
                                    simSlot = 0,
                                    operatorName = "MTS",
                                    packageType = PackageType.MINUTES,
                                    limit = 100L,
                                    used = 10L,
                                    remaining = 90L,
                                    unit = MeasurementUnit.MINUTES,
                                    periodStart = 0L,
                                    periodEnd = 0L,
                                    sourceType = DataSourceType.SYSTEM,
                                    lastUpdateTime = 0L,
                                ),
                        ),
                ),
            ),
        capabilities = emptyList(),
    )

    @Test
    fun `test recover does not leak monitoring coroutines`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
            coordinator.status
            repeat(10) { backgroundScope.launch { coordinator.recover() } }
            testScheduler.advanceUntilIdle()
            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "LeakTest"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, atLeastOnce()).updateAll()
        }
}
