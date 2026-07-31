package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCoordinatorTest {
    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private val snapshotFlow = MutableStateFlow(createEmptySnapshot())

    @Before
    fun setup() {
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
    }

    @Test
    fun `snapshot change triggers widget update with debounce`() =
        runTest {
            UpdateCoordinator(dataRefreshNotifier, widgetUpdater, { usageStateRepository }, backgroundScope)

            // 1. First meaningful snapshot
            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.value = s1
            testScheduler.advanceTimeBy(1000)
            verify(widgetUpdater).updateAll()

            // 2. Second change
            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.value = s2
            testScheduler.advanceTimeBy(1000)
            verify(widgetUpdater, times(2)).updateAll()
        }

    @Test
    fun `identical content snapshots do not trigger redundant updates`() =
        runTest {
            UpdateCoordinator(dataRefreshNotifier, widgetUpdater, { usageStateRepository }, backgroundScope)

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.value = s1
            testScheduler.advanceTimeBy(1000)
            verify(widgetUpdater, times(1)).updateAll()

            // Same content, different timestamp
            val s2 = createSnapshot(timestamp = 2000L, content = "A")
            snapshotFlow.value = s2
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
                    { usageStateRepository },
                    backgroundScope,
                )

            coordinator.notifyDataChanged(updateWidget = true, force = true)

            verify(widgetUpdater).updateAll()
            verify(dataRefreshNotifier).notifyRefresh(isManual = true)
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
                    operator = "MTS",
                    isActive = true,
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
