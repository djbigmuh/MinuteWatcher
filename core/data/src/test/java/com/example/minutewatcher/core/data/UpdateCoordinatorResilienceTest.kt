package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateCoordinatorResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dataRefreshNotifier: DataRefreshNotifier = mock()
    private val widgetUpdater: WidgetUpdater = mock()
    private val usageStateRepository: UsageStateRepository = mock()

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

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // Advance time to cover 2 timeouts + delays (10s + 1s + 10s + 2s)
            testScheduler.advanceTimeBy(30.seconds)
            testScheduler.runCurrent()

            // Should eventually succeed and reach Active status
            assertEquals(TrackingStatus.Active, coordinator.status.value)
            // One call per attempt in loop (3) + one call for the main observation loop (1) = 4
            assertTrue("Call count should be at least 4, but was $callCount", callCount >= 4)
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

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // 3 attempts * 10s + delays
            testScheduler.advanceTimeBy(60.seconds)
            testScheduler.runCurrent()

            val status = coordinator.status.value
            assertTrue("Status should be Error after max retries, but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
            assertEquals(3, callCount)
        }

    @Test
    fun `regression - recover resets state and starts new observation`() =
        runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 1000L, emptyList(), emptyList())
            val snapshotFlow = MutableStateFlow(snapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())

            val coordinator =
                UpdateCoordinator(
                    dataRefreshNotifier,
                    widgetUpdater,
                    usageStateRepository,
                    backgroundScope,
                )

            // Wait for initial Active status
            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()
            assertEquals(TrackingStatus.Active, coordinator.status.value)

            coordinator.recover()

            // Wait for recovery startup
            testScheduler.advanceTimeBy(10.seconds)
            runCurrent()

            assertEquals("Should be Active after recovery", TrackingStatus.Active, coordinator.status.value)
        }
}
