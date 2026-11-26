package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.resetAppLoggerForTest
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.DataSourceType
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.MeasurementUnit
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.SemanticEntity
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UpdateCoordinatorTakeoverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val widgetUpdater = FakeWidgetUpdater()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()

    private fun createCoordinator(scope: kotlinx.coroutines.CoroutineScope): UpdateCoordinator {
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
        whenever(simWidgetVisibilityRepository.observeSimSettings()).thenReturn(MutableSharedFlow())
        return UpdateCoordinator(
            dataRefreshNotifier = dataRefreshNotifier,
            widgetUpdater = widgetUpdater,
            usageStateRepository = usageStateRepository,
            appSettingsRepository = appSettingsRepository,
            simWidgetVisibilityRepository = simWidgetVisibilityRepository,
            scope = scope,
        )
    }

    @Before
    fun setup() {
        resetAppLoggerForTest()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(
            MutableStateFlow(listOf(mock())),
        )
    }

    @Test
    fun `forced update takeover does not kill observation cycle`() =
        runTest {
            val snapshotFlow = MutableStateFlow(createSnapshot(500L, "Initial", used = 0))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()

            // 1. Initial update
            testScheduler.advanceTimeBy(200.milliseconds)
            testScheduler.runCurrent()
            assertEquals("Initial update should have happened", 1, widgetUpdater.callCount)

            // 2. Trigger reactive update that will hang
            widgetUpdater.shouldHang = true
            val hangStarted = CompletableDeferred<Unit>()
            widgetUpdater.onUpdateStarted = { if (widgetUpdater.shouldHang) hangStarted.complete(Unit) }

            // Emit snapshot "A" with different 'used' value to ensure hash mismatch
            snapshotFlow.value = createSnapshot(1000L, "A", used = 10)
            testScheduler.advanceUntilIdle()

            withTimeout(5.seconds) {
                hangStarted.await()
            }
            assertEquals("Should have started second update", 2, widgetUpdater.callCount)

            // 3. Perform forced update (takeover)
            widgetUpdater.shouldHang = false
            widgetUpdater.onUpdateStarted = null

            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()

            assertEquals("Should have 3 calls after forced update", 3, widgetUpdater.callCount)
            verify(dataRefreshNotifier).notifyRefresh(eq(true), any())

            widgetUpdater.releaseHangingUpdate()
            testScheduler.advanceUntilIdle()

            // 4. Check observation cycle is still alive by emitting another snapshot
            snapshotFlow.value = createSnapshot(2000L, "B", used = 20)
            testScheduler.advanceTimeBy(200.milliseconds)
            testScheduler.runCurrent()

            assertEquals("Observation cycle should still be alive", 4, widgetUpdater.callCount)
        }

    @Test
    fun `two concurrent forced updates during hanging reactive update preserve observation cycle`() =
        runTest {
            val snapshotFlow = MutableStateFlow(createSnapshot(500L, "Initial", used = 0))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()

            // 1. Initial update
            testScheduler.advanceTimeBy(200.milliseconds)
            testScheduler.runCurrent()
            assertEquals("Initial update should have happened", 1, widgetUpdater.callCount)

            // 2. Trigger reactive update that will hang
            widgetUpdater.shouldHang = true
            val hangStarted = CompletableDeferred<Unit>()
            widgetUpdater.onUpdateStarted = { if (widgetUpdater.shouldHang) hangStarted.complete(Unit) }

            snapshotFlow.value = createSnapshot(1000L, "A", used = 10)
            testScheduler.advanceUntilIdle()

            withTimeout(5.seconds) {
                hangStarted.await()
            }
            assertEquals("Should have started second update", 2, widgetUpdater.callCount)

            // 3. Perform two concurrent forced updates
            widgetUpdater.shouldHang = false
            widgetUpdater.onUpdateStarted = null

            val forcedJob1 = launch { coordinator.notifyDataChanged(updateWidget = true, force = true) }
            val forcedJob2 = launch { coordinator.notifyDataChanged(updateWidget = true, force = true) }
            testScheduler.advanceUntilIdle()

            widgetUpdater.releaseHangingUpdate()
            testScheduler.advanceUntilIdle()

            withTimeoutOrNull(30.seconds) {
                forcedJob1.join()
                forcedJob2.join()
            } ?: fail("Timed out waiting for forced updates to complete")

            val callCountAfterForced = widgetUpdater.callCount

            // 4. Emit another snapshot and verify observation cycle continues immediately
            snapshotFlow.value = createSnapshot(2000L, "B", used = 20)
            testScheduler.advanceTimeBy(200.milliseconds)
            testScheduler.runCurrent()

            assertEquals(
                "Observation cycle should still be alive and handle snapshot change",
                callCountAfterForced + 1,
                widgetUpdater.callCount,
            )
        }

    @Test
    fun `forced update clears takeoverUpdateJob after canceled job completion`() =
        runTest {
            val snapshotFlow = MutableStateFlow(createSnapshot(500L, "Initial", used = 0))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()

            testScheduler.advanceUntilIdle()

            widgetUpdater.shouldHang = true
            val hangStarted = CompletableDeferred<Unit>()
            widgetUpdater.onUpdateStarted = { if (widgetUpdater.shouldHang) hangStarted.complete(Unit) }

            snapshotFlow.value = createSnapshot(1000L, "A", used = 10)
            testScheduler.advanceUntilIdle()

            withTimeout(5.seconds) {
                hangStarted.await()
            }

            widgetUpdater.shouldHang = false
            widgetUpdater.onUpdateStarted = null

            val forcedJob = launch { coordinator.notifyDataChanged(updateWidget = true, force = true) }
            testScheduler.advanceUntilIdle()

            widgetUpdater.releaseHangingUpdate()
            testScheduler.advanceUntilIdle()

            withTimeout(5.seconds) {
                forcedJob.join()
            }

            assertNull("takeoverUpdateJob should be cleared after completion", coordinator.takeoverUpdateJob.get())
        }

    private fun createSnapshot(
        timestamp: Long,
        content: String,
        used: Long,
    ) = GlobalSnapshot(
        version = SchemaVersion(1, 0, content.hashCode()),
        timestamp = timestamp,
        isManualUpdate = false,
        suppressWidgetUpdate = false,
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
                                    used = used,
                                    remaining = 100L - used,
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

    private class FakeWidgetUpdater : WidgetUpdater {
        var callCount = 0
        var shouldHang = false
        var onUpdateStarted: (() -> Unit)? = null
        private var hangingDeferred: CompletableDeferred<Unit>? = null

        override suspend fun updateAll(): WidgetUpdateResult {
            System.err.println("TRACE_TAKEOVER: updateAll called, new callCount=${callCount + 1}")
            callCount++
            onUpdateStarted?.invoke()

            if (shouldHang) {
                val deferred = CompletableDeferred<Unit>()
                hangingDeferred = deferred
                deferred.await()
            }
            return WidgetUpdateResult.Success()
        }

        fun releaseHangingUpdate() {
            hangingDeferred?.complete(Unit)
        }

        override suspend fun recover() = Unit
    }
}
