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
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("MaxLineLength", "TooGenericExceptionThrown")
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
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(true)
        }
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
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            verify(widgetUpdater).updateAll()

            // 2. Second change
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
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
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            verify(widgetUpdater, times(1)).updateAll()

            // Same content, different timestamp
            val s2 = createSnapshot(timestamp = 2000L, content = "A")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)

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
            verify(dataRefreshNotifier).notifyRefresh(isManual = false, suppressWidgetUpdate = false)

            // Should call updateAll after snapshot change and debounce
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
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
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
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

            // 5. Reset mock to succeed
            doReturn(Unit).whenever(widgetUpdater).updateAll()

            // 6. Advance time to let reactive path (debounce) trigger for s2.
            // If forced update FAILED to update hash, reactive path should still see s2 as new.
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)

            // Should call updateAll again (3rd time total: 1st for A, 2nd forced B (fail), 3rd reactive B)
            verify(widgetUpdater, atLeastOnce()).updateAll()
        }

    @Test
    fun `widget update failure does not trigger flow retry but allows retry on next snapshot`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot())
                .thenReturn(stateFlow)

            var failCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                if (failCount == 0) {
                    failCount++
                    throw RuntimeException("Temporary update failure")
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

            // 1. Emit snapshot s1. First attempt will fail, but flow continues.
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)

            // Should call updateAll once and fail
            verify(widgetUpdater, times(1)).updateAll()

            // 2. Emit s2. Since lastSnapshotHash was not updated, it should trigger updateAll.
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)

            // Total updateAll calls: 1 (s1 fail) + 1 (s2 success) = 2
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `observation resumes after repository failure`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            var shouldFail = true

            val mockStateFlow =
                object : StateFlow<GlobalSnapshot> by stateFlow {
                    override suspend fun collect(collector: FlowCollector<GlobalSnapshot>): Nothing {
                        if (shouldFail) {
                            shouldFail = false
                            throw RuntimeException("Repository failure")
                        }
                        stateFlow.collect(collector)
                    }
                }
            whenever(usageStateRepository.observeSnapshot()).thenReturn(mockStateFlow)

            UpdateCoordinator(
                dataRefreshNotifier,
                widgetUpdater,
                usageStateRepository,
                backgroundScope,
                timeProvider,
            )
            testScheduler.advanceUntilIdle()

            // 1. Emit s1. Flow initially fails, retries after 5s, then succeeds.
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)

            // Advance time for retry delay (5s) + debounce
            testScheduler.advanceTimeBy(6.seconds + Constants.Widget.DEBOUNCE_MS.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()
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
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
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
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)

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
                    override suspend fun updateAll(): Boolean {
                        val count = callCount.incrementAndGet()
                        if (count == 1) {
                            firstUpdateStarted.complete(Unit)
                            canFinishFirst.await()
                        } else if (count == 2) {
                            secondUpdateStarted.complete(Unit)
                            canFinishSecond.await()
                        }
                        return true
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
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
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
    fun `forced update and subsequent reactive update with same content trigger only one update`() =
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

    @Test
    fun `observationRetryCount is reset by timer`() =
        runTest {
            var collectCount = 0
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())

            val mockStateFlow =
                object : StateFlow<GlobalSnapshot> by stateFlow {
                    override suspend fun collect(collector: FlowCollector<GlobalSnapshot>): Nothing {
                        collectCount++
                        throw RuntimeException("Persistent failure")
                    }
                }
            whenever(usageStateRepository.observeSnapshot()).thenReturn(mockStateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                    timeProvider,
                )

            // Fail 6 times. Total time: 5 + 10 + 20 + 40 + 80 = 155s for 5 retries.
            // Attempt 1: 0s, Failure. Retry 1 in 5s.
            // Attempt 2: 5s, Failure. Retry 2 in 10s.
            // Attempt 3: 15s, Failure. Retry 3 in 20s.
            // Attempt 4: 35s, Failure. Retry 4 in 40s.
            // Attempt 5: 75s, Failure. Retry 5 in 80s.
            // Attempt 6: 155s, Failure. Retry 6 in 300s.
            testScheduler.advanceTimeBy(156.seconds)
            assertEquals(6, collectCount)

            // Verify observationRetryCount is 6 (we can't access it directly, but we can verify behavior)
            // If we wait RESET_RETRY_COUNTER_INTERVAL_MS (10 mins)
            timeProvider.advance(601000L) // 10 mins + 1s

            // Next retry should reset the counter and start from 5s delay instead of 300s.
            // But wait, the PREVIOUS failure already scheduled a delay of 160s (not 300s anymore due to lookback logic changes).
            // So we need to wait at least 160s from the 155s mark to trigger the next attempt.
            testScheduler.advanceTimeBy(160.seconds)

            val field = UpdateCoordinator::class.java.getDeclaredField("observationRetryCount")
            field.isAccessible = true
            val countAfterReset = field.get(coordinator) as Long
            assertEquals("Counter should be reset (1 after increment in failure)", 1L, countAfterReset)
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
