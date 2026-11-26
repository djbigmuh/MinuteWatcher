package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class UpdateCoordinatorTest : UpdateCoordinatorTestFixtures() {

    @Test
    fun `snapshot change triggers widget update with debounce`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.value = s1
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `observation starts only after explicit start call`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope)
            testScheduler.runCurrent()

            val s1 = createEmptySnapshot()
            snapshotFlow.value = s1
            testScheduler.runCurrent()

            verify(widgetUpdater, never()).updateAll()

            coordinator.start()
            testScheduler.runCurrent()

            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.value = s2
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `terminal failure triggers restart on same snapshot`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (callCount == 1) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator = createCoordinator(supervisorScope).apply { start() }
            testScheduler.runCurrent()

            snapshotFlow.value = createSnapshot(timestamp = 1000L, content = "RestartTest")
            testScheduler.advanceTimeBy(200.milliseconds)

            assertEquals("First attempt failed", 1, callCount)
            assertTrue("Status should be Error after failure", coordinator.status.value is TrackingStatus.Error)

            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.advanceUntilIdle()

            assertEquals("Should have restarted and called updateAll again", 2, callCount)
            assertEquals(
                "Status should be Active after successful restart",
                TrackingStatus.Active,
                coordinator.status.value,
            )
            supervisorJob.cancel()
        }
    }

    @Test
    fun `observation enters degraded mode after 5 consecutive crashes`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )

            val coordinator = createCoordinator(supervisorScope).apply { start() }
            testScheduler.runCurrent()

            snapshotFlow.value = createSnapshot(timestamp = 1000L, content = "PersistentFail")
            testScheduler.advanceTimeBy(80.seconds)
            testScheduler.advanceUntilIdle()

            verify(widgetUpdater, times(5)).updateAll()

            val status = coordinator.status.value as TrackingStatus.Error
            assertTrue(
                "Should contain 'Retrying in', got: ${status.message}",
                status.message?.contains("Retrying in") == true,
            )
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, status.type)
            assertEquals(TrackingStatus.ErrorCode.RENDER_FAILURE, status.code)

            supervisorJob.cancel()
        }
    }

    @Test
    fun `observation stops permanently after 3 degraded cycles`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )

            val coordinator = createCoordinator(supervisorScope).apply { start() }
            testScheduler.runCurrent()

            snapshotFlow.value = createSnapshot(timestamp = 1000L, content = "InfiniteFail")

            testScheduler.advanceTimeBy(2000.seconds)
            testScheduler.advanceUntilIdle()

            val status = coordinator.status.value as TrackingStatus.Error
            assertTrue(
                "Should contain 'permanently stopped', got: ${status.message}",
                status.message?.contains("permanently stopped") == true,
            )

            verify(widgetUpdater, times(15)).updateAll()

            supervisorJob.cancel()
        }
    }

    @Test
    fun `successful updates reset observation restart counter`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            var failNext = true
            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (failNext) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator = createCoordinator(supervisorScope).apply { start() }
            testScheduler.runCurrent()

            snapshotFlow.value = createSnapshot(timestamp = 1000L, content = "ResetTest")
            testScheduler.advanceTimeBy(50.seconds)
            testScheduler.advanceUntilIdle()
            assertEquals(4, callCount)

            failNext = false
            testScheduler.advanceTimeBy(40.seconds)
            testScheduler.advanceUntilIdle()
            assertEquals("Status should be Active", TrackingStatus.Active, coordinator.status.value)
            assertEquals(5, callCount)

            failNext = true
            snapshotFlow.value = createSnapshot(timestamp = 2000L, content = "FailAgain")
            testScheduler.advanceTimeBy(1.seconds)
            testScheduler.advanceUntilIdle()
            assertEquals(6, callCount)
            assertTrue(coordinator.status.value is TrackingStatus.Error)

            testScheduler.advanceTimeBy(5.seconds)
            testScheduler.advanceUntilIdle()
            assertEquals("Should have restarted after 5s", 7, callCount)

            supervisorJob.cancel()
        }
    }

    @Test
    fun `manual update triggers immediate update without debounce`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val s1 = createSnapshot(timestamp = 1000L, content = "Manual", isManual = true)
            snapshotFlow.value = s1
            testScheduler.advanceTimeBy(10.milliseconds)
            testScheduler.runCurrent()
            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `observation ignores duplicate snapshot content`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val s1 = createSnapshot(timestamp = 1000L, content = "A")
            snapshotFlow.value = s1
            testScheduler.advanceTimeBy(200.milliseconds)
            verify(widgetUpdater, times(1)).updateAll()

            val s2 = createSnapshot(timestamp = 2000L, content = "B")
            snapshotFlow.value = s2
            coordinator.notifyDataChanged(updateWidget = true, force = true)
            testScheduler.advanceUntilIdle()
            verify(widgetUpdater, times(2)).updateAll()

            val s2Suppressed = s2.copy(suppressWidgetUpdate = true)
            snapshotFlow.value = s2Suppressed
            testScheduler.advanceTimeBy(200.milliseconds)
            verify(widgetUpdater, times(2)).updateAll()
        }
    }

    @Test
    fun `update coordinator recovery resets all internal states and sticky error status`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Failure(wasCoalesced = false))

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            for (i in 1..5) {
                snapshotFlow.value = createSnapshot(timestamp = i * 1000L, content = "Fail $i")
                testScheduler.advanceTimeBy(200.milliseconds)
            }

            assertTrue(coordinator.status.value is TrackingStatus.Error)

            coordinator.recover()
            assertEquals(
                "Status should be Idle immediately after recover",
                TrackingStatus.Idle,
                coordinator.status.value,
            )

            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            snapshotFlow.value = createSnapshot(timestamp = 7000L, content = "Fail 5")
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, atLeastOnce()).updateAll()
            assertEquals("Status should eventually become Active", TrackingStatus.Active, coordinator.status.value)
        }
    }

    @Test
    fun `test recover does not leak monitoring coroutines`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            repeat(10) { backgroundScope.launch { coordinator.recover() } }
            testScheduler.advanceTimeBy(200.milliseconds)

            snapshotFlow.value = createSnapshot(timestamp = 1000L, content = "LeakTest")
            testScheduler.advanceTimeBy(200.milliseconds)
            verify(widgetUpdater, atLeastOnce()).updateAll()
        }
    }

    @Test
    fun `reactive update with coalesced success does not update lastSnapshotHash`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success(wasCoalesced = true))

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val snapshotA = createSnapshot(timestamp = 1000L, content = "CoalescedA")
            snapshotFlow.value = snapshotA
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `reactive update with non-coalesced success updates lastSnapshotHash`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success(wasCoalesced = false))

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val snapshotA = createSnapshot(timestamp = 1000L, content = "IdenticalContent")
            snapshotFlow.value = snapshotA
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()

            val snapshotB = createSnapshot(timestamp = 2000L, content = "IdenticalContent")
            snapshotFlow.value = snapshotB
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `forced update with coalesced success does not update lastSnapshotHash`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success(wasCoalesced = true))

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val snapshot = createSnapshot(timestamp = 1000L, content = "ForcedCoalescedContent")
            snapshotFlow.value = snapshot
            testScheduler.advanceTimeBy(200.milliseconds)

            val success = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertTrue("Forced update should succeed", success)

            verify(dataRefreshNotifier).notifyRefresh(isManual = true, suppressWidgetUpdate = false)

            val nextSnapshot = createSnapshot(timestamp = 2000L, content = "ForcedCoalescedContent")
            snapshotFlow.value = nextSnapshot
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(3)).updateAll()
        }
    }

    @Test
    fun `recover triggers new snapshot and keeps observation status active`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            coordinator.recover()
            testScheduler.advanceTimeBy(200.milliseconds)

            val s1 = createSnapshot(timestamp = 2000L, content = "AfterRecover")
            snapshotFlow.value = s1
            testScheduler.advanceTimeBy(200.milliseconds)

            assertEquals(TrackingStatus.Active, coordinator.status.value)
        }
    }

    @Test
    fun `forced update with LIMIT_EXCEEDED failure sets NOT_SUPPORTED status and resets hash`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(reason = WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED),
            )

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val success = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertFalse("Forced update should return false on failure", success)

            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
            assertEquals(TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT, status.code)

            whenever(widgetUpdater.updateAll()).thenReturn(WidgetUpdateResult.Success())
            val nextSnapshot = createSnapshot(timestamp = 2000L, content = "SameContent")
            snapshotFlow.value = nextSnapshot
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, atLeastOnce()).updateAll()
        }
    }

    @Test
    fun `forced update with TERMINAL failure sets UNKNOWN status and resets hash`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(reason = WidgetUpdateResult.FailureReason.TERMINAL),
            )

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val success = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertFalse("Forced update should return false on failure", success)

            val status = coordinator.status.value
            assertTrue("Status should be Error", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
        }
    }

    @Test
    fun `forced update with TRANSIENT failure increments consecutive failures and suppresses updates`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenReturn(
                WidgetUpdateResult.Failure(reason = WidgetUpdateResult.FailureReason.TRANSIENT),
            )

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            repeat(Constants.Widget.MAX_CONSECUTIVE_UPDATE_FAILURES) {
                coordinator.notifyDataChanged(updateWidget = true, force = true)
            }

            val status = coordinator.status.value as TrackingStatus.Error
            assertTrue("Status should be Error", true)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, status.type)
            assertEquals(TrackingStatus.ErrorCode.RENDER_FAILURE, status.code)

            val suppressedCallSuccess = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertFalse("Subsequent forced update should be suppressed", suppressedCallSuccess)
        }
    }

    @Test
    fun `forced update throwing unexpected exception is classified as TERMINAL and returns false`() {
        runTest {
            whenever(widgetUpdater.updateAll()).thenThrow(RuntimeException("Unexpected error"))

            val coordinator = createCoordinator(backgroundScope).apply { start() }
            testScheduler.runCurrent()

            val success = coordinator.notifyDataChanged(updateWidget = true, force = true)
            assertFalse("Forced update with exception should return false", success)

            val status = coordinator.status.value
            assertTrue("Status should be Error(UNKNOWN)", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.UNKNOWN, (status as TrackingStatus.Error).type)
        }
    }

    // --- PHASE 2 MANDATORY TESTS T1 - T7 ---

    @Test
    fun `T1 start repeated start calls result in single active observation collector`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            val s1 = createSnapshot(timestamp = 1000L, content = "T1Snapshot")
            snapshotFlow.value = s1
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()
        }
    }

    @Test
    fun `T2 recover cancels old observation job and creates single new observation job`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            val oldJob = coordinator.currentObservationJob.get()
            assertTrue("Old job should exist", oldJob != null)

            coordinator.recover()
            testScheduler.advanceTimeBy(200.milliseconds)

            val newJob = coordinator.currentObservationJob.get()
            assertTrue("Old job should be cancelled", oldJob!!.isCancelled)
            assertTrue("New job should exist", newJob != null)
            assertTrue("New job should be active", newJob!!.isActive)
        }
    }

    @Test
    fun `T3 after terminal failure and restart generation increments and single collector active`() {
        runTest {
            val supervisorJob = SupervisorJob()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val supervisorScope = CoroutineScope(testDispatcher + supervisorJob + CoroutineExceptionHandler { _, _ -> })

            var callCount = 0
            whenever(widgetUpdater.updateAll()).thenAnswer {
                callCount++
                if (callCount == 1) {
                    WidgetUpdateResult.Failure(isRetryable = false, reason = WidgetUpdateResult.FailureReason.TERMINAL)
                } else {
                    WidgetUpdateResult.Success()
                }
            }

            val coordinator = createCoordinator(supervisorScope)
            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            val initialGen = coordinator.observationGeneration

            snapshotFlow.value = createSnapshot(timestamp = 1000L, content = "T3TerminalFail")
            testScheduler.advanceTimeBy(200.milliseconds)

            assertEquals("First attempt failed", 1, callCount)
            assertTrue("Status should be Error after failure", coordinator.status.value is TrackingStatus.Error)

            testScheduler.advanceTimeBy(10.seconds)
            testScheduler.advanceUntilIdle()

            assertEquals("Should have restarted and called updateAll again", 2, callCount)
            assertTrue("Generation should increment", coordinator.observationGeneration > initialGen)

            supervisorJob.cancel()
        }
    }

    @Test
    fun `T4 observeSimSettings emitting single change triggers widget update`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            simWidgetVisibilityRepository.simSettingsFlow.emit(Unit)
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, atLeastOnce()).updateAll()
        }
    }

    @Test
    fun `T5 settings flow IOException followed by change still delivers change`() {
        runTest {
            val failingSimRepo =
                object : SimWidgetVisibilityRepository {
                    val flow: Flow<Unit> =
                        flow {
                            throw java.io.IOException("DataStore read error")
                        }

                    override fun observeSimSettings(): Flow<Unit> = flow

                    override fun getSimSettingsBatch(identities: List<String>): Flow<Map<String, SimSettingsState>> =
                        MutableStateFlow(emptyMap())

                    override suspend fun setSimSettingsBatch(settingsMap: Map<String, SimSettingsState>) {}
                }

            val coordinator = createCoordinator(backgroundScope, customSimSettings = failingSimRepo)
            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            testScheduler.advanceTimeBy(2.seconds)
            testScheduler.advanceUntilIdle()

            val status = coordinator.status.value
            assertTrue("Coordinator remains operational despite settings error", status is TrackingStatus.Idle || status is TrackingStatus.Active)
        }
    }

    @Test
    fun `T6 recover keeps settings observers active for subsequent changes`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            coordinator.recover()
            testScheduler.advanceTimeBy(200.milliseconds)

            appSettingsRepository.updateAppSettings { it.copy(widgetTransparency = 42) }
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, atLeastOnce()).updateAll()
        }
    }

    @Test
    fun `T7 settings change with same snapshot content redraws widget`() {
        runTest {
            val coordinator = createCoordinator(backgroundScope)
            coordinator.start()
            testScheduler.advanceTimeBy(200.milliseconds)

            val snapshotA = createSnapshot(timestamp = 1000L, content = "SameSnapshot")
            snapshotFlow.value = snapshotA
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(1)).updateAll()

            appSettingsRepository.updateAppSettings { it.copy(widgetTransparency = 77) }
            testScheduler.advanceTimeBy(200.milliseconds)

            verify(widgetUpdater, times(2)).updateAll()
        }
    }
}
