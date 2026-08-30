package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

// Opt-in для InternalCoroutinesApi: MutableSharedFlow реализует SharedFlow,
// часть контрактов которого помечена как unstable. Использование ограничено
// тестовым кодом и не влияет на production-контракты.

/**
 * @Suppress("MaxLineLength") is required because:
 * 1. Long comments in Russian describing complex concurrent test scenarios.
 */
@Suppress("MaxLineLength")
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateCoordinatorTest {
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private val snapshotFlow = MutableSharedFlow<GlobalSnapshot>(replay = 1, extraBufferCapacity = 10)

    @Before
    fun setup() {
        initTestLogger()
        snapshotFlow.tryEmit(createEmptySnapshot())
        runBlocking {
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
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

            // 3. Setup the s2 snapshot with suppressWidgetUpdate=true as if it came from repository after notifyRefresh
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
    fun `recover resets failure counter and allows updates`() {
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

            // 1. Fail 5 times
            for (i in 1..5) {
                snapshotFlow.tryEmit(createSnapshot(timestamp = i * 1000L, content = "Fail $i"))
                testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
            }
            verify(widgetUpdater, times(5)).updateAll()

            // 2. 6th attempt should be suppressed
            snapshotFlow.tryEmit(createSnapshot(timestamp = 6000L, content = "Fail 6"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(5)).updateAll()

            // 3. Recover
            coordinator.recover()
            testScheduler.runCurrent()

            // 4. Now successful update
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            snapshotFlow.tryEmit(createSnapshot(timestamp = 7000L, content = "Success"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(6)).updateAll()
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
    fun `status transitions to Error after max failures and back to Idle on recover`() {
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

            // 1. Fail 5 times
            for (i in 1..5) {
                snapshotFlow.tryEmit(createSnapshot(timestamp = i * 1000L, content = "Fail $i"))
                testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
                testScheduler.runCurrent()
            }

            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)

            // 2. Recover
            coordinator.recover()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Idle, coordinator.status.value)
        }
    }

    @Test
    fun `processSnapshot preserves specific error on TransactionTooLargeException`() {
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            // Wrap in Exception as classifyException takes Exception
            val limitExceededException = Exception(android.os.TransactionTooLargeException())
            whenever(widgetUpdater.updateAll()).thenThrow(limitExceededException)

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
            snapshotFlow.tryEmit(createSnapshot(timestamp = 1000L, content = "Large"))
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
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
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob)

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
    fun `observation stops restarting after 5 consecutive crashes`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob)

            val stateFlow =
                snapshotFlow.stateIn(
                    supervisorScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            // Throw on every update attempt to force observation crash
            whenever(widgetUpdater.updateAll()).thenThrow(Throwable("Persistent crash"))

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    supervisorScope,
                )
            coordinator.status
            testScheduler.runCurrent()

            // 1. Emit snapshot to trigger observation loop
            snapshotFlow.emit(createSnapshot(timestamp = 1000L, content = "CrashLoop"))

            // Allow time for 5 crashes and 4 restarts (since 5th restart is suppressed)
            // Delays:
            // 1st crash -> restart #1 in 5s
            // 2nd crash -> restart #2 in 10s
            // 3rd crash -> restart #3 in 20s
            // 4th crash -> restart #4 in 40s
            // 5th crash -> limit reached, no more restarts

            val totalWaitTime = (5 + 10 + 20 + 40 + 5) * 1000L // ~80s total + some buffer
            testScheduler.advanceTimeBy(totalWaitTime.milliseconds)
            testScheduler.advanceUntilIdle()

            // Status should be Error
            val status = coordinator.status.value
            assertTrue("Status should be Error after max restarts", status is TrackingStatus.Error)
            assertEquals(
                "Widget observation failed permanently after 5 restarts",
                (status as TrackingStatus.Error).message
            )

            // Verify number of calls to updateAll.
            // Each observation attempt calls updateAll once after debounce.
            // 1 initial + 4 restarts = 5 calls total.
            verify(widgetUpdater, times(5)).updateAll()

            supervisorJob.cancel()
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
    ) = GlobalSnapshot(
        version = SchemaVersion(1, 0, content.hashCode()),
        timestamp = timestamp,
        simSnapshots =
            listOf(
                com.example.minutewatcher.core.model.SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = "MTS",
                    isActive = true,
                    autoResetDay = 1,
                    entities =
                        mapOf(
                            com.example.minutewatcher.core.model.PackageType.MINUTES to
                                com.example.minutewatcher.core.model.SemanticEntity(
                                    id = content, // Use content here to change hash
                                    simSlot = 0,
                                    operatorName = "MTS",
                                    packageType = com.example.minutewatcher.core.model.PackageType.MINUTES,
                                    limit = 100L,
                                    used = 10L,
                                    remaining = 90L,
                                    unit = com.example.minutewatcher.core.model.MeasurementUnit.MINUTES,
                                    periodStart = 0L,
                                    periodEnd = 0L,
                                    sourceType = com.example.minutewatcher.core.model.DataSourceType.SYSTEM,
                                    lastUpdateTime = 0L,
                                ),
                        ),
                ),
            ),
        capabilities = emptyList(),
    )
}
