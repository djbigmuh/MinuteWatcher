package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.NoOpLogger

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private val snapshotFlow = MutableSharedFlow<GlobalSnapshot>(replay = 1, extraBufferCapacity = 10)

    @Before
    fun setup() {
        AppLogger.init(mock(), NoOpLogger())
        snapshotFlow.tryEmit(createEmptySnapshot())
    }

    @Test
    fun `snapshot change triggers widget update with debounce`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.notifyDataChanged(updateWidget = false, force = false) // Trigger observation

            // 1. First meaningful snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(1000)
            verify(widgetUpdater).updateAll()

            // 2. Second change
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(1000)
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `identical content snapshots do not trigger redundant updates`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )
            coordinator.notifyDataChanged(updateWidget = false, force = false) // Trigger observation

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(1000)
            verify(widgetUpdater, times(1)).updateAll()

            // Same content, different timestamp
            val s2 = createSnapshot(timestamp = 2000L, content = "A")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(1000)

            // Still only 1 call
            verify(widgetUpdater, times(1)).updateAll()
        }

    @Test
    fun `notifyDataChanged with force triggers immediate widget update`() =
        runTest {
            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            coordinator.notifyDataChanged(updateWidget = true, force = true)

            verify(widgetUpdater).updateAll()
            verify(dataRefreshNotifier).notifyRefresh(isManual = true)
        }

    @Test
    fun `notifyDataChanged without force does NOT trigger immediate widget update`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            coordinator.notifyDataChanged(updateWidget = true, force = false)

            // Should NOT call updateAll immediately
            verify(widgetUpdater, times(0)).updateAll()
            verify(dataRefreshNotifier).notifyRefresh(isManual = false)

            // Should call updateAll after snapshot change and debounce
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(1000)
            verify(widgetUpdater, times(1)).updateAll()
        }

    @Test
    fun `manual snapshot is skipped by reactive flow if recently forced`() =
        runTest {
            val stateFlow = snapshotFlow.stateIn(backgroundScope, SharingStarted.Eagerly, createEmptySnapshot())
            whenever(usageStateRepository.observeSnapshot()).thenReturn(stateFlow)

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // 1. Force update
            coordinator.notifyDataChanged(updateWidget = true, force = true)
            verify(widgetUpdater, times(1)).updateAll()

            // 2. Emit manual snapshot (e.g. from the refresh triggered by force)
            val s1 = createSnapshot(timestamp = 1000L, content = "A").copy(isManualUpdate = true)
            snapshotFlow.tryEmit(s1)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)

            // Should still be 1 call (the forced one), reactive one skipped
            verify(widgetUpdater, times(1)).updateAll()

            // 3. Emit another snapshot - should be processed because gate is reset
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.tryEmit(s2)
            testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS + 100)
            verify(widgetUpdater, times(2)).updateAll()
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
