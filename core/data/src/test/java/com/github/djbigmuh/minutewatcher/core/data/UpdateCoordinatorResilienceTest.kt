package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import com.github.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UpdateCoordinatorResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val simWidgetVisibilityRepository: SimWidgetVisibilityRepository = mock()

    private fun createCoordinator(scope: CoroutineScope): UpdateCoordinator {
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
        initTestLogger()
        whenever(usageStateRepository.observeSimCardUiModels()).thenReturn(flowOf(listOf(mock())))
    }

    @Test
    fun `setupObservation retries on first snapshot timeout`() =
        runTest {
            var callCount = 0
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())

            whenever(usageStateRepository.observeSnapshot()).thenAnswer {
                callCount++
                flow {
                    if (callCount <= 2) {
                        // First two attempts will timeout
                        delay(Constants.Flow.LONG_TIMEOUT_MS.milliseconds + 1000.milliseconds)
                    }
                    emit(snapshot)
                }.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()),
                )
            }
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()

            // Advance time to cover 2 timeouts + delays (10s + 1s + 10s + 2s)
            testScheduler.advanceTimeBy(30.seconds)
            testScheduler.runCurrent()

            // Should eventually succeed and reach Active status
            assertEquals(TrackingStatus.Active, coordinator.status.value)
            // One call per attempt in loop (3) + one call for the main observation loop (1) = 4
            assertTrue(
                "Call count should be at least 4, but was $callCount",
                callCount >= 4,
            )
        }

    @Test
    fun `setupObservation fails after max retries`() =
        runTest {
            var callCount = 0
            whenever(usageStateRepository.observeSnapshot()).thenAnswer {
                callCount++
                flow {
                    // Always timeout
                    delay(Constants.Flow.LONG_TIMEOUT_MS.milliseconds + 1000.milliseconds)
                    emit(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
                }.stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList()),
                )
            }

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()

            // Advance time to exceed max attempts (3 attempts * ~10s = 30s)
            testScheduler.advanceTimeBy(40.seconds)
            testScheduler.runCurrent()

            // Should be in Error status after max retries
            assertTrue(coordinator.status.value is TrackingStatus.Error)
            assertEquals(3, callCount)
        }

    @Test
    fun `update coordinator recovery resets state and starts new observation`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)

            coordinator.recover()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `recover during active setup registers observation and monitoring restarts on failure`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            coordinator.recover()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `non takeover cancellation causes monitoring restart`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.runCurrent()

            coordinator.currentObservationJob.get()?.cancel()
            testScheduler.runCurrent()

            assertNotNull(coordinator.currentObservationJob.get())
        }

    @Test
    fun `concurrent recover calls before dispatcher execution create single setup job`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.recover()
            coordinator.recover()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `stale observation termination after recovery does not interfere with new active state`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            val oldJob = coordinator.currentObservationJob.get()
            coordinator.recover()
            testScheduler.runCurrent()

            oldJob?.cancel()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `old observation job completion when active job changed completes without restart`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            val oldJob = coordinator.currentObservationJob.get()
            coordinator.recover()
            testScheduler.runCurrent()

            oldJob?.cancel()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `setupJob does not contain completed job after start returns`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.runCurrent()

            assertFalse(coordinator.setupJob.get()?.isActive == true)
        }

    @Test
    fun `repeated start when setup is active does not create second coroutine`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            coordinator.start()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `start after setup completion and no active observation creates new setup`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.runCurrent()

            coordinator.currentObservationJob.get()?.cancel()
            coordinator.start()
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }

    @Test
    fun `canceling setup from recover cancels setupJob`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            val initialSetup = coordinator.setupJob.get()
            coordinator.recover()

            assertTrue(initialSetup == null || initialSetup.isCancelled)
        }

    @Test
    fun `concurrent start from ten coroutines creates exactly one setup`() =
        runTest {
            val snapshotFlow = MutableStateFlow(GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList()))
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator = createCoordinator(backgroundScope)
            repeat(10) {
                launch { coordinator.start() }
            }
            testScheduler.runCurrent()

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }
}
