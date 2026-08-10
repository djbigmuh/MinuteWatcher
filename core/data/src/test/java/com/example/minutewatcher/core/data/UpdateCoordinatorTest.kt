package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.NoOpLogger
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private val timeProvider = FixedTimeProvider(currentTime = 1000000L)

    private val snapshotFlow = MutableSharedFlow<GlobalSnapshot>(replay = 1, extraBufferCapacity = 10)

    @Before
    fun setup() {
        AppLogger.init(mock(), NoOpLogger())
        snapshotFlow.tryEmit(createEmptySnapshot())
        // notifyRefresh is a suspend function returning Unit.
        // Mockito handles it automatically (returns immediately).
    }

    @Test
    fun `snapshot change triggers widget update with debounce`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )
            coordinator.notifyDataChanged(updateWidget = false, force = false) // Trigger observation

            // 1. First meaningful snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater).updateAll()

            // 2. Second change
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `identical content snapshots do not trigger redundant updates`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )
            coordinator.notifyDataChanged(updateWidget = false, force = false) // Trigger observation

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater, times(1)).updateAll()

            // Same content, different timestamp
            val s2 = createSnapshot(timestamp = 2000L, content = "A")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)

            // Still only 1 call
            verify(widgetUpdater, times(1)).updateAll()
        }

    @Test
    fun `notifyDataChanged with force triggers immediate widget update`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )
            testScheduler.advanceUntilIdle()

            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()

            verify(widgetUpdater).updateAll()
            verify(dataRefreshNotifier).notifyRefresh(isManual = true, suppressWidgetUpdate = true)
        }

    @Test
    fun `notifyDataChanged without force does NOT trigger immediate widget update`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )

            coordinator.notifyDataChanged(updateWidget = true, force = false)

            // Should NOT call updateAll immediately
            verify(widgetUpdater, times(0)).updateAll()
            verify(dataRefreshNotifier).notifyRefresh(isManual = false)

            // Should call updateAll after snapshot change and debounce
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater, times(1)).updateAll()
        }

    @Test
    fun `two consecutive forced updates both trigger widget update`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )
            testScheduler.advanceUntilIdle()

            // First force
            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()

            // Second force
            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()

            // Should be 2 calls
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `forced update failure does not update hash allowing subsequent reactive update`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            // Setup: init will call updateAll for first snapshot, let it succeed to set initial state
            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )
            testScheduler.advanceUntilIdle()

            // 1. Emit meaningful snapshot s1
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater, times(1)).updateAll()

            // 2. Setup failure for NEXT update
            doThrow(RuntimeException("Limit exceeded")).whenever(widgetUpdater).updateAll()

            // 3. Emit NEW snapshot s2
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)
            // advanceTimeBy NOT yet to avoid reactive path triggering first

            // 4. Trigger FORCED update for s2 (which fails)
            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()

            verify(widgetUpdater, times(2)).updateAll() // The forced one called and failed

            // 5. Reset mock to succeed
            doReturn(Unit).whenever(widgetUpdater).updateAll()

            // 6. Advance time to let reactive path (debounce) trigger for s2.
            // If forced update FAILED to update hash, reactive path should still see s2 as new.
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)

            // Should call updateAll again (3rd time total: 1st for A, 2nd forced B (fail), 3rd reactive B)
            verify(widgetUpdater, times(3)).updateAll()
        }

    @Test
    fun `widget update failure resets hash and allows retry`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            var failCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                if (failCount == 0) {
                    failCount++
                    error("Temporary update failure")
                }
                Unit
            }

            UpdateCoordinator(
                dataRefreshNotifier,
                widgetUpdater,
                usageStateRepository,
                backgroundScope,
                timeProvider,
            )

            // 1. Emit snapshot s1. First attempt will fail and trigger retry loop.
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)

            // Wait for failure and retry delay (coordinator has 5s initial delay)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)

            // consecutiveErrors becomes 1, delay is 5 * (2^1) = 10 seconds.
            testScheduler.advanceTimeBy(12.seconds)

            // The retry happens, it collects s1 again.
            // debounce on the new collection
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            testScheduler.advanceUntilIdle()

            // Total updateAll calls: 1 (fail) + 1 (success on retry) = 2
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `observation resumes after repository failure`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            UpdateCoordinator(
                dataRefreshNotifier,
                widgetUpdater,
                usageStateRepository,
                backgroundScope,
                timeProvider,
            )
            testScheduler.advanceUntilIdle()

            // 1. Emit s1, success
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater, times(1)).updateAll()

            // 2. Setup next collect to fail
            // We can't easily make the flow itself fail if it's a StateFlow,
            // but we can make the next onEach call fail.
            var shouldFail = true
            whenever(widgetUpdater.updateAll()).thenAnswer {
                if (shouldFail) {
                    shouldFail = false
                    error("Downstream failure")
                }
                Unit
            }

            // 3. Emit s2, onEach fails, retryWhen should catch and delay
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)

            // Retry delay is 5s (attempt 0)
            testScheduler.advanceTimeBy(6.seconds)

            // Debounce after retry
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)

            // Should have called updateAll for B (second time, now success)
            verify(widgetUpdater, times(3)).updateAll() // 1 (A) + 1 (B fail) + 1 (B success)
        }

    @Test
    fun `forced update suppresses reactive update for the same snapshot`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )
            testScheduler.advanceUntilIdle()

            // 1. Emit snapshot s1 and let reactive update finish
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater, times(1)).updateAll()

            // 2. Perform forced update for a NEW snapshot s2
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)

            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()

            // Should call updateAll immediately
            verify(widgetUpdater, times(2)).updateAll()

            // 3. Setup the s2 snapshot with suppressWidgetUpdate=true as if it came from repository after notifyRefresh
            val s2Suppressed = s2.copy(suppressWidgetUpdate = true)
            snapshotFlow.tryEmit(s2Suppressed)

            // 4. Advance time to let reactive path trigger
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)

            // Should NOT call updateAll again (count should remain 2)
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `concurrent force and reactive updates are sequential due to mutex`() =
        runTest {
            val stateFlow =
                snapshotFlow.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    createEmptySnapshot(),
                )
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            val callCount = AtomicInteger(0)
            val firstUpdateStarted = CompletableDeferred<Unit>()
            val secondUpdateStarted = CompletableDeferred<Unit>()
            val canFinishFirst = CompletableDeferred<Unit>()
            val canFinishSecond = CompletableDeferred<Unit>()

            val fakeWidgetUpdater =
                object : WidgetUpdater {
                    override suspend fun updateAll() {
                        val count = callCount.incrementAndGet()
                        if (count == 1) {
                            firstUpdateStarted.complete(Unit)
                            canFinishFirst.await()
                        } else if (count == 2) {
                            secondUpdateStarted.complete(Unit)
                            canFinishSecond.await()
                        }
                    }
                }

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    fakeWidgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )
            testScheduler.advanceUntilIdle()

            // 1. Trigger reactive update
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)

            // Advance time to allow reactive coroutine to pick up snapshot and enter updateAll
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            firstUpdateStarted.await()

            assertEquals(1, callCount.get())

            // 2. Trigger forced update immediately while reactive is still running.
            val forcedUpdateJob =
                launch {
                    coordinator.notifyDataChanged(updateWidget = true, force = true)
                }
            runCurrent()

            // Still 1 call because forced is waiting for the lock
            assertEquals(1, callCount.get())

            // 3. Let reactive update finish
            canFinishFirst.complete(Unit)

            // Wait for forced update to acquire lock and start
            secondUpdateStarted.await()
            assertEquals(2, callCount.get())

            // 4. Let forced update finish
            canFinishSecond.complete(Unit)
            testScheduler.advanceUntilIdle()

            // 5. Verification:
            assertEquals(2, callCount.get())
            assertTrue(forcedUpdateJob.isCompleted)
        }

    @Test
    fun `observation continues indefinitely after many failures`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            var failCount = 0
            val maxFails = 15
            whenever(widgetUpdater.updateAll()).thenAnswer {
                if (failCount < maxFails) {
                    failCount++
                    error("Failure $failCount")
                }
                Unit
            }

            UpdateCoordinator(
                dataRefreshNotifier,
                widgetUpdater,
                usageStateRepository,
                backgroundScope,
                timeProvider,
            )

            // Emit snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)

            // Advance through 15 failures
            // INITIAL_RETRY_DELAY_SECONDS = 5s
            // MAX_RETRY_EXPONENT = 6 (2^6 = 64) -> Max initial-based delay is 5 * 64 = 320s, capped at 300s.

            // Just advance a lot of time to cover 15 retries.
            // 15 * 300s = 4500s
            testScheduler.advanceTimeBy(5000.seconds)
            testScheduler.advanceUntilIdle()

            // After 15 failures, the 16th attempt should succeed
            // (failCount will be 15, then it will return Unit)

            // Total updateAll calls: 15 (fail) + 1 (success) = 16
            verify(widgetUpdater, times(16)).updateAll()
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
        version = SchemaVersion(1, 0, 0),
        timestamp = timestamp,
        simSnapshots =
            listOf(
                com.example.minutewatcher.core.model.SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operator = "MTS",
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
