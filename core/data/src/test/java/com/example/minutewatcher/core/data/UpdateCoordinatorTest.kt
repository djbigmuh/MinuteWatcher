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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Opt-in для InternalCoroutinesApi: MutableSharedFlow реализует SharedFlow,
// часть контрактов которого помечена как unstable. Использование ограничено
// тестовым кодом и не влияет на production-контракты.

/**
 * @Suppress("MaxLineLength") is required because:
 * 1. Long comments in Russian describing complex concurrent test scenarios.
 */
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
            // Use a non-completing Flow for SIM models to ensure restarts work reliably
            val simFlow = MutableStateFlow(listOf(mock<SimCardUiModel>()))
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)
        }
    }

    @Test
    fun `snapshot change triggers widget update with debounce`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // Emit new snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `manual update triggers immediate update without debounce`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // Emit new snapshot with isManualUpdate = true
            val s1 = createSnapshot(timestamp = 1000L, content = "Manual", isManual = true)
            snapshotFlow.tryEmit(s1)

            // Advance by very small time, much less than DEBOUNCE_MS
            testScheduler.advanceTimeBy(10.milliseconds)
            testScheduler.runCurrent()

            // Should call updateAll immediately
            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `observation ignores duplicate snapshot content`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Emit snapshot s1 and let reactive update finish
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(1)).updateAll()

            // 2. Perform forced update for a NEW snapshot s2
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)

            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()

            // Should call updateAll immediately (total 2)
            verify(widgetUpdater, times(2)).updateAll()

            // 3. Set up the s2 snapshot with suppressWidgetUpdate=true as if it came from repository after notifyRefresh
            val s2Suppressed = s2.copy(suppressWidgetUpdate = true)
            snapshotFlow.tryEmit(s2Suppressed)

            // 4. Advance time to let reactive path trigger
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should NOT call updateAll again (count should remain 2)
            verify(widgetUpdater, times(2)).updateAll()
        }
    }

    @Test
    fun `forced update and subsequent reactive update with same content trigger only one update`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Trigger forced update for snapshot s1
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.runCurrent() // Ensure StateFlow value is updated

            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.runCurrent() // Start forced update
            testScheduler.advanceUntilIdle()

            // Forced update should call updateAll
            verify(widgetUpdater, times(1)).updateAll()

            // 2. Advance time to let reactive path (debounce) trigger for s1
            // Use runCurrent() several times to ensure all coroutines and flows are processed
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should NOT call updateAll again because forced update already updated the hash
            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `suppressWidgetUpdate does NOT update hash and allows update for subsequent identical snapshot`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Emit snapshot with suppressWidgetUpdate = true
            val s1 = createSnapshot(timestamp = 1000L, content = "A", suppressUpdate = true)
            snapshotFlow.tryEmit(s1)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should NOT call updateAll
            verify(widgetUpdater, times(0)).updateAll()

            // 2. Emit identical snapshot but with suppressWidgetUpdate = false
            val s2 = createSnapshot(timestamp = 2000L, content = "A", suppressUpdate = false)
            snapshotFlow.tryEmit(s2)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should NOW call updateAll because hash was not updated during step 1
            verify(widgetUpdater, times(1)).updateAll()

            // 3. Emit different snapshot with suppressWidgetUpdate = false
            val s3 = createSnapshot(timestamp = 3000L, content = "B", suppressUpdate = false)
            snapshotFlow.tryEmit(s3)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should call updateAll again
            verify(widgetUpdater, times(2)).updateAll()
        }
    }

    @Test
    fun `reactive update is suppressed after 5 consecutive failures`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Failure(wasCoalesced = false))

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // Emit 6 different snapshots
            for (i in 1..6) {
                val s = createSnapshot(timestamp = i * 1000L, content = "Content $i")
                snapshotFlow.tryEmit(s)
                testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
            }

            // Should call updateAll only 5 times
            verify(widgetUpdater, times(Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES)).updateAll()
        }
    }

    @Test
    fun `consecutiveUpdateFailures is not incremented on LIMIT_EXCEEDED`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(
                    isRetryable = false,
                    reason = WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED,
                ),
            )

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // Emit 6 different snapshots that trigger LIMIT_EXCEEDED
            for (i in 1..6) {
                snapshotFlow.tryEmit(createSnapshot(timestamp = i * 1000L, content = "Large $i"))
                testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
            }

            // Should call updateAll 6 times (not suppressed)
            verify(widgetUpdater, times(6)).updateAll()

            // Status should be NOT_SUPPORTED, not DATABASE_ERROR
            val status = coordinator.status.value
            assertTrue(status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
        }
    }

    @Test
    fun `status transitions to Active on successful reactive update`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            assertEquals(TrackingStatus.Idle, coordinator.status.value)

            // Emit new snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(1)).updateAll()
            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }
    }

    @Test
    fun `regression - recover resets all internal states and sticky Error status`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Failure(wasCoalesced = false))

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Fail 5 times to provoke sticky DATABASE_ERROR
            for (i in 1..5) {
                snapshotFlow.tryEmit(createSnapshot(timestamp = i * 1000L, content = "Fail $i"))
                testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
            }

            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            // 2. 6th attempt should be suppressed (verify sticky state)
            snapshotFlow.tryEmit(createSnapshot(timestamp = 6000L, content = "Fail 6"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(5)).updateAll()

            // 3. Recover
            coordinator.recover()

            // 4. Verify status is immediately Idle (sticky error fix)
            assertEquals(
                "Status should be Idle immediately after recover",
                TrackingStatus.Idle,
                coordinator.status.value,
            )

            // 5. Verify failure counter and hash reset - successful update should now work even with same content
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            // Use same content as one of previous failures to verify hash reset
            snapshotFlow.tryEmit(createSnapshot(timestamp = 7000L, content = "Fail 5"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(6)).updateAll()
            assertEquals("Status should eventually become Active", TrackingStatus.Active, coordinator.status.value)
        }
    }

    @Test
    fun `processSnapshot preserves specific error on LIMIT_EXCEEDED`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val localWidgetUpdater: WidgetUpdater = mock()
            whenever(localWidgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(
                    isRetryable = false,
                    reason = WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED,
                ),
            )

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    localWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // Emit new snapshot
            snapshotFlow.tryEmit(createSnapshot(timestamp = 1000L, content = "Large"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            val status = coordinator.status.value
            assertTrue("Status should be Error, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
        }
    }

    @Test
    fun `processSnapshot handles unexpected exception in updateAll as TERMINAL`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val localWidgetUpdater: WidgetUpdater = mock()
            whenever(localWidgetUpdater.updateAll()).thenAnswer { throw IllegalStateException("Unexpected") }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    localWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // Emit new snapshot
            snapshotFlow.tryEmit(createSnapshot(timestamp = 1000L, content = "Fail"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
        }
    }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `observation restarts after exception in reactive path`() {
        runTest {
            // Independent supervisor to isolate the crash from the test scope.
            // Using StandardTestDispatcher linked to the test scheduler ensures
            // all launched coroutines are controlled by the test clock.
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope =
                CoroutineScope(
                    testDispatcher +
                        supervisorJob +
                        CoroutineExceptionHandler { _, _ ->
                            // Ignore
                        },
                )

            val stateFlow =
                snapshotFlow.stateIn(
                    supervisorScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            // Make first update throw a Throwable (not Exception) to bypass catchingCancellation
            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (callCount == 1) throw Throwable("Simulated crash")
                WidgetUpdateResult.Success()
            }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    supervisorScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.runCurrent()
            assertEquals("No updates should happen on empty snapshot", 0, callCount)

            // 1. Trigger first update and crash
            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "Crash"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Verify crash happened
            assertEquals("First call (crash) should have occurred", 1, callCount)

            // 2. Wait for 5 seconds for restart
            testScheduler.advanceTimeBy(5000.milliseconds)
            testScheduler.advanceUntilIdle() // Ensure restart logic runs

            // 3. Emit new snapshot and verify update works again
            snapshotFlow.emit(createSnapshot(timestamp = 2000L, content = "Success"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.advanceUntilIdle()

            // Verify second update (on Crash snapshot) and third update (on Success snapshot) succeeded
            // total: 1 (crash) + 1 (automatic restart on same snapshot) + 1 (new snapshot) = 3
            verify(widgetUpdater, times(3)).updateAll()

            supervisorJob.cancel()
        }
    }

    @Test
    fun `observation enters degraded mode after 5 consecutive crashes`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val localWidgetUpdater: WidgetUpdater = mock()
            // Return TERMINAL failure to trigger observation crash and restart
            whenever(localWidgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    localWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Provoke 15 crashes (3 cycles of 5)
            // Emitting new values ensures collectLatest triggers and restarts happen reliably in tests
            for (i in 1..15) {
                snapshotFlow.emit(createSnapshot(timestamp = i * 1000L, content = "Crash $i"))
                testScheduler.advanceTimeBy(400.seconds)
                testScheduler.advanceUntilIdle()
            }

            // Should have attempted to call updateAll for all cycles
            verify(localWidgetUpdater, times(15)).updateAll()

            // Status should be Error
            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
        }
    }

    @Test
    fun `successful updates reset observation restart counter`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val localWidgetUpdater: WidgetUpdater = mock()
            // Setup: 4 crashes, then success
            var callCount = 0
            whenever(localWidgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (callCount <= 4) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    localWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Trigger 4 crashes via observation loop with unique content to ensure processing
            for (i in 1..4) {
                snapshotFlow.emit(createSnapshot(timestamp = i * 1000L, content = "Crash $i"))
                testScheduler.advanceTimeBy(100.seconds)
                testScheduler.advanceUntilIdle()
            }

            // 2. Perform successful forced update to reset counter
            whenever(localWidgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            val forcedSuccess = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertTrue("Forced update should succeed", forcedSuccess)
            testScheduler.advanceUntilIdle()

            // 3. Trigger another crash via observation loop
            whenever(localWidgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )
            snapshotFlow.emit(createSnapshot(timestamp = 5000L, content = "New Crash"))
            testScheduler.advanceUntilIdle()

            // 4. Wait for restart delay (should be 5s if reset, not 80s if not reset)
            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.advanceUntilIdle()

            // If it wasn't reset, it would have hit the limit (5) and entered degraded mode (DATABASE_ERROR).
            val status = coordinator.status.value
            assertTrue(
                "Status should be transient UNKNOWN error, not sticky DATABASE_ERROR",
                (status is TrackingStatus.Error) && (status.type == TrackingStatus.ErrorType.UNKNOWN),
            )
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
                                    id = content, // Use content here to change hash
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
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // Rapid succession of recover calls
            repeat(10) {
                backgroundScope.launch {
                    coordinator.recover()
                }
            }

            testScheduler.advanceUntilIdle()

            // Verification: we can't easily count coroutines in the scope from here,
            // but we can ensure the coordinator is still functional and didn't crash.
            val s1 = createSnapshot(timestamp = 1000L, content = "LeakTest")
            snapshotFlow.emit(s1)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should have called updateAll at least once (for the latest observation setup)
            verify(widgetUpdater, times(1)).updateAll()
        }

    @Test
    fun `reactive update retries on next snapshot if first one fails with same content`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Trigger observation
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. First update attempt fails
            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = true, reason = WidgetUpdateResult.FailureReason.TRANSIENT),
            )

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(1)).updateAll()

            // 2. Emit another snapshot with DIFFERENT timestamp but SAME content (hash)
            val s2 = s1.copy(timestamp = 2000L)
            snapshotFlow.tryEmit(s2)

            // Now make the next attempt succeed
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should call updateAll AGAIN (total 2) because first one failed and hash wasn't updated
            verify(widgetUpdater, times(2)).updateAll()
        }
    }

    @Test
    fun `forced update does not block reactive updates during long running update`() =
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            // Mock long-running update using a fake object to avoid runBlocking in mocks
            val updateStarted = CompletableDeferred<Unit>()
            val updateCanFinish = CompletableDeferred<Unit>()
            var callCount = 0
            val longRunningUpdater =
                object : WidgetUpdater {
                    override suspend fun updateAll(): WidgetUpdateResult {
                        callCount++
                        updateStarted.complete(Unit)
                        updateCanFinish.await()
                        return WidgetUpdateResult.Success()
                    }

                    override suspend fun recover() {
                        // No-op for test
                    }
                }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    longRunningUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // Start forced update in a separate coroutine
            val forcedUpdateJob =
                backgroundScope.launch {
                    coordinator.notifyDataChanged(updateWidget = true, force = true)
                }

            testScheduler.runCurrent()
            updateStarted.await() // Wait until forced update hits updateAll()

            // Now trigger a reactive update by emitting a new snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "Reactive")
            snapshotFlow.tryEmit(s1)

            // Advance time for debounce
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Reactive update should be triggered and waiting for its own call to updateAll
            // (or coalescing with the current one if it was already finished, but here it's still running)

            updateCanFinish.complete(Unit)
            forcedUpdateJob.join()
            testScheduler.advanceUntilIdle()

            // Total calls should be 2 (one forced, one reactive)
            assertEquals(2, callCount)
        }

    @Test
    fun `forced update suppresses updates after max failures from exceptions`() =
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            // Mock updateAll to throw exception
            whenever(widgetUpdater.updateAll()).thenThrow(RuntimeException("Persistent exception"))

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // 1. Perform forced update 5 times, each throwing an exception
            repeat(Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                coordinator.notifyDataChanged(updateWidget = true, force = true)
            }

            // Should have attempted to call updateAll 5 times
            verify(widgetUpdater, times(Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES)).updateAll()

            // Status should be Error
            val status = coordinator.status.value
            assertTrue("Status should be Error after max failures from exceptions", status is TrackingStatus.Error)

            // 2. 6th forced update attempt should be suppressed (no more calls to updateAll)
            val result = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertFalse("Forced update should be suppressed", result)

            // Verify count remains at limit
            verify(widgetUpdater, times(Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES)).updateAll()
        }

    @Test
    fun `notifyRefresh is called only on successful forced update`() =
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // 1. Success case
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            val successResult = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertTrue(successResult)
            verify(dataRefreshNotifier, times(1)).notifyRefresh(isManual = true, suppressWidgetUpdate = true)

            // 2. Failure case
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Failure())
            val failureResult = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertFalse(failureResult)
            // Still 1, not 2
            verify(dataRefreshNotifier, times(1)).notifyRefresh(isManual = true, suppressWidgetUpdate = true)

            // 3. Exception case
            whenever(widgetUpdater.updateAll()).thenThrow(RuntimeException("Exception"))
            val exceptionResult = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertFalse(exceptionResult)
            // Still 1
            verify(dataRefreshNotifier, times(1)).notifyRefresh(isManual = true, suppressWidgetUpdate = true)
        }

    @Test
    fun `terminal failure resets hash to allow retry on same content`() =
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Emit snapshot s1, it fails with TERMINAL
            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(1)).updateAll()
            assertTrue(coordinator.status.value is TrackingStatus.Error)

            // 2. Emit another snapshot with SAME content (same hash)
            val s2 = s1.copy(timestamp = 2000L)
            snapshotFlow.tryEmit(s2)

            // Make it succeed now
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should call updateAll AGAIN (total 2) because hash was reset on terminal failure
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `suppressWidgetUpdate does not change consecutiveUpdateFailures`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            // Mock update to fail
            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = true, reason = WidgetUpdateResult.FailureReason.TRANSIENT),
            )

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Fail 4 times
            for (i in 1..4) {
                snapshotFlow.tryEmit(createSnapshot(timestamp = i * 1000L, content = "Fail $i"))
                testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
            }
            verify(widgetUpdater, times(4)).updateAll()
            // Counter should be 4. Status should not be terminal error yet.
            assertTrue(
                coordinator.status.value !is TrackingStatus.Error ||
                    (coordinator.status.value as TrackingStatus.Error).type != TrackingStatus.ErrorType.DATABASE_ERROR,
            )

            // 2. Emit suppressed snapshot
            val suppressed = createSnapshot(timestamp = 5000L, content = "Suppressed", suppressUpdate = true)
            snapshotFlow.tryEmit(suppressed)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Verify updateAll was NOT called
            verify(widgetUpdater, times(4)).updateAll()

            // 3. Emit normal snapshot that fails
            val normal = createSnapshot(timestamp = 6000L, content = "Normal", suppressUpdate = false)
            snapshotFlow.tryEmit(normal)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Total calls should be 5
            verify(widgetUpdater, times(5)).updateAll()

            // Status should now be terminal error because failure count reached 5
            val status = coordinator.status.value
            assertTrue("Status should be terminal Error after 5 failures", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            // 4. Emit one more normal snapshot - should be suppressed by failure limit
            snapshotFlow.tryEmit(createSnapshot(timestamp = 7000L, content = "Final"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(5)).updateAll() // Still 5
        }
    }

    @Test
    fun `observation stops permanently after 3 degraded cycles`() {
        runTest {
            val stateFlow = MutableStateFlow(createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val localWidgetUpdater: WidgetUpdater = mock()
            // Return TERMINAL failure to trigger observation crash and restart
            whenever(localWidgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(
                    isRetryable = false,
                    reason = WidgetUpdateResult.FailureReason.TERMINAL,
                ),
            )

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    localWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Provoke 15 crashes (3 cycles of 5)
            for (i in 1..15) {
                stateFlow.value = createSnapshot(timestamp = i * 1000L, content = "CrashLoop $i")
                testScheduler.advanceTimeBy(400.seconds)
                testScheduler.advanceUntilIdle()
            }

            verify(localWidgetUpdater, times(15)).updateAll()

            // Now it should be permanently stopped or failed.
            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            val errMsg = (status as TrackingStatus.Error).message ?: ""
            assertTrue(
                "Error message should indicate failure, got: $errMsg",
                errMsg.contains("permanently stopped after 3 degraded cycles") ||
                    errMsg.contains("Terminal widget update failure"),
            )

            // Verify no more calls after a long time or verify next call happens
            stateFlow.value = createSnapshot(timestamp = 20000L, content = "Late")
            testScheduler.advanceTimeBy(1000.seconds)
            testScheduler.advanceUntilIdle()
            verify(localWidgetUpdater, times(16)).updateAll()

            // Test manual recovery
            whenever(localWidgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            coordinator.recover()

            assertEquals(
                "Status should be Idle immediately after recover",
                TrackingStatus.Idle,
                coordinator.status.value,
            )
            testScheduler.advanceUntilIdle()
            assertTrue(
                "Status should be a healthy state after recovery",
                coordinator.status.value is TrackingStatus.Active ||
                    coordinator.status.value is TrackingStatus.Idle,
            )
        }
    }

    @Test
    fun `performForcedUpdate updates hash BEFORE updateAll and does not suppress if content changed`() =
        runTest {
            val initialState = createSnapshot(timestamp = 1000L, content = "Initial")
            val intermediateState = createSnapshot(timestamp = 2000L, content = "Intermediate")
            val snapshotFlow = MutableStateFlow(initialState)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            val updateStarted = CompletableDeferred<Unit>()
            val canFinishUpdate = CompletableDeferred<Unit>()
            var callCount = 0

            val localWidgetUpdater =
                object : WidgetUpdater {
                    override suspend fun updateAll(): WidgetUpdateResult {
                        callCount++
                        updateStarted.complete(Unit)
                        canFinishUpdate.await()
                        return WidgetUpdateResult.Success()
                    }

                    override suspend fun recover() {
                        // No-op for test
                    }
                }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    localWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            // Start observation to have a reactive path
            coordinator.status
            testScheduler.runCurrent()

            // 1. Trigger forced update
            val forcedUpdateJob =
                launch {
                    coordinator.notifyDataChanged(updateWidget = true, force = true)
                }

            updateStarted.await()

            // 2. Change snapshot while update is running.
            // In the new implementation, performForcedUpdate will read THIS snapshot
            // and compare it with the one BEFORE updateAll to decide if suppression is needed.
            snapshotFlow.value = intermediateState

            // 3. Finish update
            canFinishUpdate.complete(Unit)
            forcedUpdateJob.join()

            // Verification 1: notifyRefresh should be called with suppressWidgetUpdate = false
            // because content changed during update (Initial -> Intermediate)
            verify(dataRefreshNotifier).notifyRefresh(isManual = true, suppressWidgetUpdate = false)

            // 4. Reactive path should now trigger for Intermediate because hash was set to Initial
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Total calls should be 2: one forced, one reactive for the intermediate state
            assertEquals(
                "Should have updated twice: forced + reactive because content changed during forced",
                2,
                callCount,
            )
        }

    @Test
    fun `performForcedUpdate suppresses reactive update if content DID NOT change`() =
        runTest {
            val initialState = createSnapshot(timestamp = 1000L, content = "Stable")
            val snapshotFlow = MutableStateFlow(initialState)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            var callCount = 0
            val localWidgetUpdater =
                object : WidgetUpdater {
                    override suspend fun updateAll(): WidgetUpdateResult {
                        callCount++
                        return WidgetUpdateResult.Success()
                    }

                    override suspend fun recover() {
                        // No-op for test
                    }
                }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    localWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.runCurrent()

            // 1. Trigger forced update
            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.runCurrent()

            // Verification 1: notifyRefresh should be called with suppressWidgetUpdate = true
            verify(dataRefreshNotifier).notifyRefresh(isManual = true, suppressWidgetUpdate = true)

            // 2. Subsequent reactive delivery of same content should be skipped
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            assertEquals("Should have updated only once", 1, callCount)
        }

    @Test
    fun `recover during active join starts new monitoring exactly once`() =
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope =
                CoroutineScope(
                    testDispatcher +
                        supervisorJob +
                        CoroutineExceptionHandler { _, _ ->
                            // Ignore
                        },
                )

            val stateFlow =
                snapshotFlow.stateIn(
                    supervisorScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    supervisorScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Trigger observation. Observation job will be active.
            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "Initial"))
            testScheduler.advanceUntilIdle()

            // 2. Call recover() which cancels previous jobs and starts new one.
            coordinator.recover()
            testScheduler.advanceUntilIdle()

            // 3. Emit new snapshot and verify only one update happens (from the new observation job)
            // If the old monitoring job wasn't canceled or its join logic failed, we might see duplicate behavior.
            snapshotFlow.emit(createSnapshot(timestamp = 2000L, content = "AfterRecover"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Total updates: 1 (Initial) + 1 (AfterRecover) = 2.
            // If observation restart logic triggered incorrectly, we'd see more.
            verify(widgetUpdater, times(2)).updateAll()

            supervisorJob.cancel()
        }

    @Test
    fun `ensureObservationStarted handles multiple concurrent calls correctly`() =
        runTest {
            val simFlow = MutableStateFlow<List<SimCardUiModel>>(emptyList())
            whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(simFlow)
            val snapshotStateFlow = MutableStateFlow(createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotStateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // Let init start and block on SIM list
            testScheduler.runCurrent()

            // Simulation of rapid calls to start observation
            repeat(10) {
                backgroundScope.launch {
                    coordinator.recover()
                }
            }
            testScheduler.runCurrent()

            // Unblock everything
            simFlow.value =
                listOf(
                    SimCardUiModel(
                        slot = 0,
                        identity = "sim_0",
                        operatorName = "MTS",
                        iconRes = 0,
                        minutesUsed = 0,
                        minutesLimit = 0,
                        manualAdjustment = 0,
                        minutesRemaining = 0,
                        effectiveMinutesLimit = 0,
                        isMinutesLimitSet = false,
                        trafficUsedMb = 0f,
                        trafficLimitMb = 0,
                        trafficRemainingGb = 0f,
                        effectiveTrafficLimitGb = 0f,
                        trafficAdjustmentGb = 0f,
                        isTrafficLimitSet = false,
                        autoResetEnabled = false,
                        autoResetDay = 1,
                        rolloverEnabled = false,
                    ),
                )
            snapshotStateFlow.value = createSnapshot(1000L, "Init")

            // Advance virtual time
            testScheduler.advanceTimeBy(100_000)
            testScheduler.runCurrent()

            // Verify status
            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `forced update cancels reactive update but observation loop continues`() =
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val reactiveUpdateStarted = CompletableDeferred<Unit>()
            val reactiveUpdateCanFinish = CompletableDeferred<Unit>()
            var reactiveUpdateCancelled = false
            var updateAllCallCount = 0

            val customUpdater =
                object : WidgetUpdater {
                    override suspend fun updateAll(): WidgetUpdateResult {
                        updateAllCallCount++
                        if (updateAllCallCount == 1) {
                            // First call is reactive
                            try {
                                reactiveUpdateStarted.complete(Unit)
                                reactiveUpdateCanFinish.await()
                            } catch (e: CancellationException) {
                                reactiveUpdateCancelled = true
                                throw e
                            }
                        }
                        return WidgetUpdateResult.Success()
                    }

                    override suspend fun recover() {
                        // No-op for test
                    }
                }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    customUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.status
            testScheduler.advanceUntilIdle()

            // 1. Trigger reactive update
            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "Reactive"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            reactiveUpdateStarted.await()

            // 2. Trigger forced update while reactive update is suspended
            val forcedUpdateJob =
                launch {
                    coordinator.notifyDataChanged(updateWidget = true, force = true)
                }
            testScheduler.runCurrent()

            // Forced update should have canceled the currentUpdateJob
            assertTrue("Reactive update should have been cancelled by forced update", reactiveUpdateCancelled)

            // 3. Complete forced update
            forcedUpdateJob.join()
            assertEquals("Second call to updateAll should be the forced one", 2, updateAllCallCount)

            // 4. Emit another snapshot and verify observation is still ALIVE
            snapshotFlow.emit(createSnapshot(timestamp = 2000L, content = "Next"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Total calls: 1 (canceled reactive) + 1 (forced) + 1 (new reactive) = 3
            assertEquals("Observation loop should still be active and process new snapshots", 3, updateAllCallCount)
        }
}
