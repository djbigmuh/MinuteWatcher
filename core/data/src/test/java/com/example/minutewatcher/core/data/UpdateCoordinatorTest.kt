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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("MaxLineLength", "TooGenericExceptionThrown")
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.InternalCoroutinesApi::class)
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
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            UpdateCoordinator(
                dataRefreshNotifier,
                widgetUpdater,
                usageStateRepository,
                backgroundScope,
                timeProvider,
            )
            testScheduler.advanceUntilIdle()

            // Emit new snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            verify(widgetUpdater, times(1)).updateAll()
        }

    @Test
    fun `observation ignores duplicate snapshot content`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            UpdateCoordinator(
                dataRefreshNotifier,
                widgetUpdater,
                usageStateRepository,
                backgroundScope,
                timeProvider,
            )
            testScheduler.advanceUntilIdle()

            // Emit s1
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(1)).updateAll()

            // Emit s2 with same content as s1 but different timestamp
            val s2 = createSnapshot(timestamp = 2000L, content = "A")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            // Should NOT trigger second update
            verify(widgetUpdater, times(1)).updateAll()
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `observation retries on repository failure with exponential backoff`() =
        runTest {
            var collectCount = 0
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())

            // Реализация StateFlow через делегирование требует @OptIn,
            // т.к. контракт collect() помечен как unstable API.
            // Это допустимо в тестовом коде для перехвата и подмены поведения collect().
            @OptIn(ExperimentalCoroutinesApi::class)
            val mockStateFlow =
                object : StateFlow<GlobalSnapshot> by stateFlow {
                    override suspend fun collect(collector: FlowCollector<GlobalSnapshot>): Nothing {
                        collectCount++
                        throw RuntimeException("Temporary failure")
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
            testScheduler.runCurrent()

            // Retries with backoff: 2s, 4s, 8s, 16s...
            repeat(10) {
                testScheduler.advanceTimeBy(10.seconds)
                testScheduler.runCurrent()
            }

            assertTrue("Should have retried several times", collectCount >= 5)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `observation resumes after repository failure`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            var shouldFail = true

            // Реализация StateFlow через делегирование требует @OptIn,
            // т.к. контракт collect() помечен как unstable API.
            // Это допустимо в тестовом коде для перехвата и подмены поведения collect().
            @OptIn(ExperimentalCoroutinesApi::class)
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

            // Emit s1. Flow initially fails, retries after some delay, then succeeds.
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)

            // Advance time for retry delay + debounce
            testScheduler.advanceTimeBy(100.seconds)
            testScheduler.runCurrent()

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
            testScheduler.runCurrent()
            firstUpdateStarted.await()

            assertEquals(1, callCount.get())

            // 2. Trigger forced update immediately while reactive is still running.
            val forcedUpdateJob =
                launch {
                    coordinator.notifyDataChanged(updateWidget = true, force = true)
                }
            testScheduler.runCurrent()

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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `observationRetryCount is reset by timer`() =
        runTest {
            var collectCount = 0
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())

            // Реализация StateFlow через делегирование требует @OptIn,
            // т.к. контракт collect() помечен как unstable API.
            // Это допустимо в тестовом коде для перехвата и подмены поведения collect().
            @OptIn(ExperimentalCoroutinesApi::class)
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

            // Let it fail 5 times
            testScheduler.advanceTimeBy(200.seconds)
            testScheduler.runCurrent()
            assertTrue("Should have retried", collectCount >= 5)

            // Mock success now
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)
            coordinator.restartObservation()
            testScheduler.runCurrent()

            // Advance time to allow next retry
            testScheduler.advanceTimeBy(100.seconds)
            testScheduler.runCurrent()

            val field = UpdateCoordinator::class.java.getDeclaredField("observationRetryCount")
            field.isAccessible = true

            // Trigger a snapshot to run processSnapshot
            val s1 = createSnapshot(timestamp = 10000L, content = "RESET")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 100.milliseconds)
            testScheduler.runCurrent()

            val countAfterReset = (field[coordinator] as java.util.concurrent.atomic.AtomicLong).get()
            assertEquals("Counter should be reset", 0L, countAfterReset)
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
