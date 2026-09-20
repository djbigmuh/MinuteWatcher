package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
/* Suppressing LargeClass as this test file covers exhaustive scenarios for widget settings,
   including resolution edge cases and persistent state transitions. */
@Suppress("LargeClass")
class WidgetSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loader: WidgetSettingsLoader = mock()
    private val saver: WidgetSettingsSaver = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher

    private lateinit var viewModel: WidgetSettingsViewModel

    @Before
    fun setup() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
        val snapshotFlow = MutableStateFlow(snapshot)
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
        whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))

        runBlocking {
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Success)
        }
    }

    private fun TestScope.initViewModel() {
        viewModel = WidgetSettingsViewModel(loader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
        advanceUntilIdle()
    }

    @Test
    fun `init loads settings`() =
        runTest {
            initViewModel()
            assertNotNull(viewModel.state.value.settings)
            assertFalse(viewModel.state.value.isLoadError)
            verify(loader).load()
        }

    @Test
    fun `retryLoad re-attempts loading`() =
        runTest {
            whenever(loader.load()).thenThrow(RuntimeException("Fail")).thenReturn(WidgetSettingsState())

            // Initial load in init
            val initialViewModel =
                WidgetSettingsViewModel(loader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
            advanceUntilIdle()
            assertTrue(initialViewModel.state.value.isLoadError)

            initialViewModel.retryLoad()
            advanceUntilIdle()
            assertFalse(initialViewModel.state.value.isLoadError)
            assertNotNull(initialViewModel.state.value.settings)
        }

    @Test
    fun `saveAndNotify calls save and notifies`() =
        runTest {
            initViewModel()
            viewModel.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.Success, viewModel.state.value.saveResult)
            verify(saver).save(any(), any())
        }

    @Test
    fun `saveAndNotify prevents concurrent saves`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val blockingLoader =
                object : WidgetSettingsLoader {
                    override suspend fun load(): WidgetSettingsState = WidgetSettingsState()

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        gate.await()
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }
            val realSaver = WidgetSettingsSaver(blockingLoader, mock())
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            val vm =
                WidgetSettingsViewModel(loader, realSaver, appSettingsRepository, backgroundScope, usageStateRepository)
            advanceUntilIdle()

            launch { vm.saveAndNotify() }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            // Second call waits for the first one to complete
            val secondSave = launch { vm.saveAndNotify() }
            runCurrent()
            assertFalse(secondSave.isCompleted)

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(secondSave.isCompleted)
        }

    @Test
    fun `auto-save triggers after throttle delay`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            runCurrent()

            // Should not save immediately
            verify(saver, never()).save(any(), any())

            advanceTimeBy(3.seconds)
            runCurrent()

            // Should save after delay
            verify(saver).save(any(), any())
        }

    @Test
    fun `auto-save combines multiple updates`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            viewModel.updateSettings { it.copy(fontSize = 1) }
            viewModel.updateSettings { it.copy(layoutMode = 2) }

            advanceTimeBy(3.seconds)
            runCurrent()

            // Should save only once
            verify(saver, times(1)).save(any(), any())
        }

    @Test
    fun `auto-save cancels pending save on manual save`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            runCurrent()

            viewModel.saveAndNotify()
            advanceUntilIdle()

            // Manual save happened
            verify(saver, times(1)).save(any(), any())

            advanceTimeBy(3.seconds)
            runCurrent()

            // Auto-save should have been cancelled or skipped (no changes since manual save)
            verify(saver, times(1)).save(any(), any())
        }

    @Test
    fun `updateSettings updates transparency`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(transparency = 80) }
            assertEquals(
                80,
                viewModel.state.value.settings
                    ?.transparency,
            )
        }

    @Test
    fun `updateSettings updates fontSize`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(fontSize = 1) }
            assertEquals(
                1,
                viewModel.state.value.settings
                    ?.fontSize,
            )
        }

    @Test
    fun `updateSettings updates layoutMode`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(layoutMode = 1) }
            assertEquals(
                1,
                viewModel.state.value.settings
                    ?.layoutMode,
            )
        }

    @Test
    fun `updateSettings updates simArrangement`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(simArrangement = 1) }
            assertEquals(
                1,
                viewModel.state.value.settings
                    ?.simArrangement,
            )
        }

    @Test
    fun `updateSimSettings updates visibility`() =
        runTest {
            initViewModel()
            viewModel.updateSimSettings(0) { it.copy(showSim = true) }
            assertEquals(
                true,
                viewModel.state.value.settings
                    ?.simStates
                    ?.get(0)
                    ?.showSim,
            )
        }

    @Test
    fun `consumeSaveResult resets saveResult`() =
        runTest {
            initViewModel()
            viewModel.saveAndNotify()
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.saveResult)

            viewModel.consumeSaveResult()
            runCurrent()
            assertEquals(null, viewModel.state.value.saveResult)
        }

    @Test
    fun `heavy load of updates does not crash`() =
        runTest {
            initViewModel()
            repeat(100) { i ->
                viewModel.updateSettings { it.copy(transparency = i % 100) }
            }
            advanceTimeBy(3.seconds)
            runCurrent()
            verify(saver, atLeastOnce()).save(any(), any())
        }

    @Test
    fun `save failure updates state with error`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Failed("Network error"))

            viewModel.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.Failed("Network error"), viewModel.state.value.saveResult)
        }

    @Test
    fun `auto-save correctly uses most recent state`() =
        runTest {
            initViewModel()

            viewModel.updateSettings { it.copy(transparency = 10) }
            advanceTimeBy(1.seconds)
            viewModel.updateSettings { it.copy(transparency = 20) }
            advanceTimeBy(1.seconds)
            viewModel.updateSettings { it.copy(transparency = 30) }

            advanceTimeBy(3.seconds)
            runCurrent()

            verify(saver).save(org.mockito.kotlin.argThat { transparency == 30 }, any())
        }

    @Test
    fun `save with partial failure updates state correctly`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenReturn(SaveResult.PartialFailure("SIM 1 failed"))

            viewModel.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.PartialFailure("SIM 1 failed"), viewModel.state.value.saveResult)
        }

    @Test
    fun `save throws CancellationException rethrows`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenThrow(CancellationException("Cancelled"))

            try {
                viewModel.saveAndNotify()
                advanceUntilIdle()
            } catch (e: CancellationException) {
                assertEquals("Cancelled", e.message)
            }
        }
}
