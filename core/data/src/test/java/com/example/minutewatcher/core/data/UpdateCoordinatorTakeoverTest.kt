package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.AppLogger
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
import com.example.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTakeoverTest {
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val widgetUpdater = FakeWidgetUpdater()

    @Before
    fun setup() {
        AppLogger.resetForTest()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(
            MutableStateFlow(listOf(mock())),
        )
    }

    @Test
    fun `forced update takeover does not kill observation cycle`() =
        runTest {
            // Snapshots must differ by fields included in content hash (e.g. used/remaining).
            // timestamp and operatorName are excluded from hash (see SnapshotUtils.calculateContentHash).
            val snapshotFlow = MutableStateFlow(createSnapshot(500L, "Initial", used = 0))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)

            // 1. Initial update
            runCurrent()
            advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            runCurrent()
            assertEquals("Initial update should have happened", 1, widgetUpdater.callCount)

            // 2. Trigger reactive update that will hang
            widgetUpdater.shouldHang = true
            val hangStarted = CompletableDeferred<Unit>()
            widgetUpdater.onUpdateStarted = { if (widgetUpdater.shouldHang) hangStarted.complete(Unit) }

            // Emit snapshot "A" with different 'used' value to ensure hash mismatch
            snapshotFlow.value = createSnapshot(1000L, "A", used = 10)

            advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            runCurrent()

            withTimeout(5.seconds) {
                hangStarted.await()
            }
            assertEquals("Should have started second update", 2, widgetUpdater.callCount)

            // 3. Perform forced update (takeover)
            widgetUpdater.shouldHang = false
            widgetUpdater.onUpdateStarted = null

            coordinator.notifyDataChanged(updateWidget = true, force = true)
            runCurrent()

            assertEquals("Should have 3 calls after forced update", 3, widgetUpdater.callCount)
            verify(dataRefreshNotifier).notifyRefresh(eq(true), any())

            widgetUpdater.releaseHangingUpdate()
            runCurrent()

            // 4. Check observation cycle is still alive by emitting another snapshot
            // Snapshot "B" must also have different 'used' value
            snapshotFlow.value = createSnapshot(2000L, "B", used = 20)

            advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            runCurrent()

            assertEquals("Observation cycle should still be alive", 4, widgetUpdater.callCount)
        }

    @Test
    fun `two concurrent forced updates during hanging reactive update preserve observation cycle`() =
        runTest {
            val snapshotFlow = MutableStateFlow(createSnapshot(500L, "Initial", used = 0))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            val coordinator =
                UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)

            // 1. Initial update
            runCurrent()
            advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            runCurrent()
            assertEquals("Initial update should have happened", 1, widgetUpdater.callCount)

            // 2. Trigger reactive update that will hang
            widgetUpdater.shouldHang = true
            val hangStarted = CompletableDeferred<Unit>()
            widgetUpdater.onUpdateStarted = { if (widgetUpdater.shouldHang) hangStarted.complete(Unit) }

            snapshotFlow.value = createSnapshot(1000L, "A", used = 10)
            advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            runCurrent()

            withTimeout(5.seconds) {
                hangStarted.await()
            }
            assertEquals("Should have started second update", 2, widgetUpdater.callCount)

            // 3. Perform two concurrent forced updates
            widgetUpdater.shouldHang = false
            widgetUpdater.onUpdateStarted = null

            val forcedJob1 = launch { coordinator.notifyDataChanged(updateWidget = true, force = true) }
            val forcedJob2 = launch { coordinator.notifyDataChanged(updateWidget = true, force = true) }
            runCurrent()

            widgetUpdater.releaseHangingUpdate()
            runCurrent()

            forcedJob1.join()
            forcedJob2.join()

            val callCountAfterForced = widgetUpdater.callCount

            // 4. Emit another snapshot and verify observation cycle continues immediately
            snapshotFlow.value = createSnapshot(2000L, "B", used = 20)
            advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            runCurrent()

            assertEquals(
                "Observation cycle should still be alive and handle snapshot change",
                callCountAfterForced + 1,
                widgetUpdater.callCount,
            )
        }

    private class FakeWidgetUpdater : WidgetUpdater {
        var callCount = 0
        var shouldHang = false
        var onUpdateStarted: (() -> Unit)? = null
        private var updateHang = CompletableDeferred<WidgetUpdateResult>()

        fun releaseHangingUpdate() {
            updateHang.complete(WidgetUpdateResult.Success())
            updateHang = CompletableDeferred()
        }

        override suspend fun updateAll(): WidgetUpdateResult {
            callCount++
            onUpdateStarted?.invoke()
            if (shouldHang) {
                return withTimeoutOrNull(30.seconds) { updateHang.await() }
                    ?: throw AssertionError("Timed out waiting for updateHang in FakeWidgetUpdater")
            }
            return WidgetUpdateResult.Success()
        }

        override suspend fun recover() {
            // no-op
        }
    }

    private fun createSnapshot(
        timestamp: Long,
        content: String,
        used: Long = 0,
    ) = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = timestamp,
        simSnapshots =
            listOf(
                SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = content,
                    entities =
                        mapOf(
                            PackageType.MINUTES to
                                SemanticEntity(
                                    id = "e1",
                                    simSlot = 0,
                                    operatorName = content,
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
}
