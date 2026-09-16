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
import com.example.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateCoordinatorTakeoverTest {

    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val testDispatcher = StandardTestDispatcher()
    private val widgetUpdater = FakeWidgetUpdater()

    @Before
    fun setup() {
        initTestLogger()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(MutableStateFlow(emptyList()))
    }

    @Test
    fun `forced update takeover does not kill observation cycle`() = runTest(testDispatcher) {
        val snapshotFlow = MutableStateFlow(createEmptySnapshot())
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

        val coordinator = UpdateCoordinator(dataRefreshNotifier, widgetUpdater, usageStateRepository, backgroundScope)
        advanceUntilIdle()

        // 1. Trigger reactive update that will hang
        widgetUpdater.shouldHang = true
        snapshotFlow.value = createSnapshot(1000L, "A")
        testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 10.milliseconds)
        runCurrent()

        widgetUpdater.updateStarted.await() // Reactive update is now hanging
        assertEquals(1, widgetUpdater.callCount)

        // 2. Perform forced update (takeover)
        widgetUpdater.shouldHang = false // Subsequent calls succeed

        launch {
            coordinator.notifyDataChanged(updateWidget = true, force = true)
        }
        runCurrent()
        advanceUntilIdle()

        // Forced update should have finished. callCount: 1 (hang) + 1 (forced) = 2
        assertEquals(2, widgetUpdater.callCount)

        // 3. Check observation cycle is still alive by emitting another snapshot
        snapshotFlow.value = createSnapshot(2000L, "B")
        testScheduler.advanceTimeBy(Constants.Widget.DEBOUNCE_MS.milliseconds + 10.milliseconds)
        runCurrent()
        advanceUntilIdle()

        // Should have been called 3 times: 1 hang, 1 forced, 1 reactive for 'B'
        assertEquals(3, widgetUpdater.callCount)
    }

    private class FakeWidgetUpdater : WidgetUpdater {
        var callCount = 0
        var shouldHang = false
        val updateStarted = CompletableDeferred<Unit>()
        val updateHang = CompletableDeferred<WidgetUpdateResult>()

        override suspend fun updateAll(): WidgetUpdateResult {
            callCount++
            if (shouldHang) {
                updateStarted.complete(Unit)
                return updateHang.await()
            }
            return WidgetUpdateResult.Success()
        }
        override suspend fun recover() {}
    }

    private fun createEmptySnapshot() = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())

    private fun createSnapshot(timestamp: Long, content: String) = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = timestamp,
        simSnapshots = listOf(
            SimSnapshot(
                slot = 0,
                identity = "sim_0",
                operatorName = content,
                entities = mapOf(
                    PackageType.MINUTES to SemanticEntity(
                        id = "e1",
                        simSlot = 0,
                        operatorName = content,
                        packageType = PackageType.MINUTES,
                        limit = 100,
                        used = 0,
                        remaining = 100,
                        unit = MeasurementUnit.MINUTES,
                        periodStart = 0,
                        periodEnd = 0,
                        lastUpdateTime = timestamp,
                        sourceType = DataSourceType.SYSTEM
                    )
                ),
                autoResetDay = 1
            )
        ),
        capabilities = emptyList()
    )
}
