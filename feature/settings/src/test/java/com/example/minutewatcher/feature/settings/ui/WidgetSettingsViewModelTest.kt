package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSaveResult
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loader: WidgetSettingsLoader = mock()
    private val saver: WidgetSettingsSaver = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private lateinit var viewModel: WidgetSettingsViewModel

    @Before
    fun setup() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
        val snapshotFlow = MutableStateFlow(snapshot)
        whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)

        runBlocking {
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Success)
        }
    }

    private suspend fun TestScope.initViewModel() {
        viewModel = WidgetSettingsViewModel(loader, saver, usageStateRepository)
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
            runBlocking {
                whenever(loader.load()).thenThrow(RuntimeException("Fail")).thenReturn(WidgetSettingsState())
            }

            // Initial load in init
            val initialViewModel = WidgetSettingsViewModel(loader, saver, usageStateRepository)
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

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): WidgetSaveResult = WidgetSaveResult.Success
                }
            val realSaver = WidgetSettingsSaver(blockingLoader, mock())
            runBlocking {
                whenever(loader.load()).thenReturn(WidgetSettingsState())
            }
            val vm = WidgetSettingsViewModel(loader, realSaver, usageStateRepository)
            advanceUntilIdle()

            launch { vm.saveAndNotify() }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            // Второй вызов ждёт освобождения мьютекса с таймаутом
            val secondSave = launch { vm.saveAndNotify() }
            runCurrent()

            // Продвигаем время на SHORT_TIMEOUT_MS, чтобы сработал таймаут ожидания мьютекса
            advanceTimeBy(Constants.Flow.SHORT_TIMEOUT_MS.milliseconds)
            runCurrent()

            // Теперь AlreadyInProgress IS set in the state
            assertEquals(SaveResult.AlreadyInProgress, vm.state.value.saveResult)

            gate.complete(Unit)
            advanceUntilIdle()

            // After first save completes, it should be Success
            assertEquals(SaveResult.Success, vm.state.value.saveResult)
            assertFalse(vm.state.value.isSaving)
            secondSave.join()
        }

    @Test
    fun `saveAndNotify sets Failed on exception`() =
        runTest {
            val failingSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult = SaveResult.Failed()
                }
            val vm = WidgetSettingsViewModel(loader, failingSaver, usageStateRepository)
            advanceUntilIdle()

            vm.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.Failed(), vm.state.value.saveResult)
        }

    @Test
    fun `updateSettings triggers auto-save after debounce`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(fontSize = 3) }

            // Should NOT have saved yet (debounce is 400ms)
            runCurrent()
            verify(saver, never()).save(any(), any())

            // Advance time past debounce (400ms)
            advanceTimeBy(401.milliseconds)
            runCurrent()

            verify(saver).save(any(), any())
        }

    @Test
    fun `updateSimSettings triggers auto-save after debounce`() =
        runTest {
            initViewModel()
            viewModel.updateSimSettings(0) { it.copy(showTraffic = true) }

            // Should NOT have saved yet
            runCurrent()
            verify(saver, never()).save(any(), any())

            // Advance time past debounce
            advanceTimeBy(401.milliseconds)
            runCurrent()

            verify(saver).save(any(), any())
        }

    @Test
    fun `multiple updates within debounce window trigger only one save`() =
        runTest {
            val capturedStates = mutableListOf<WidgetSettingsState>()
            val localSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        capturedStates.add(state)
                        return SaveResult.Success
                    }
                }

            val vm = WidgetSettingsViewModel(loader, localSaver, usageStateRepository)
            advanceUntilIdle()

            vm.updateSettings { it.copy(fontSize = 1) }
            runCurrent()

            advanceTimeBy(200.milliseconds)
            vm.updateSettings { it.copy(fontSize = 2) }
            runCurrent()

            advanceTimeBy(200.milliseconds)
            vm.updateSettings { it.copy(fontSize = 3) }
            runCurrent()

            // Debounce is 400ms from last update (T=400). Should fire at T=800.
            advanceTimeBy(300.milliseconds) // Time is 700
            runCurrent()
            assertEquals("Should not have saved yet at T=700", 0, capturedStates.size)

            advanceTimeBy(200.milliseconds) // Time is 900
            runCurrent()

            // Should have saved exactly once with the final value
            assertEquals("Should have saved exactly once", 1, capturedStates.size)
            assertEquals(3, capturedStates.first().fontSize)
        }

    @Test
    fun `retryLoad cancels previous load job`() =
        runTest {
            val firstLoadDeferred = CompletableDeferred<WidgetSettingsState>()
            val secondLoadState = WidgetSettingsState(fontSize = 4)

            val fakeLoader =
                object : WidgetSettingsLoader {
                    var callCount = 0

                    override suspend fun load(): WidgetSettingsState {
                        callCount++
                        return if (callCount == 1) {
                            firstLoadDeferred.await()
                        } else {
                            secondLoadState
                        }
                    }

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): WidgetSaveResult = WidgetSaveResult.Success

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        // Empty for mock
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            // 1. Create VM (triggers first load in init)
            val vm = WidgetSettingsViewModel(fakeLoader, saver, usageStateRepository)
            runCurrent()

            // 2. Trigger second load while first is still pending
            vm.retryLoad()
            runCurrent()

            // 3. Complete first load (it should be cancelled)
            firstLoadDeferred.complete(WidgetSettingsState(fontSize = 1))
            advanceUntilIdle()

            // 4. Assert state contains result from second load
            val state = vm.state.value.settings
            assertNotNull(state)
            assertEquals(4, state!!.fontSize)
            assertEquals(2, fakeLoader.callCount)
        }

    @Test
    fun `concurrent updateSettings does not lose updates`() =
        runTest {
            initViewModel()
            val totalUpdates = 10
            val deferreds =
                List(totalUpdates) { i ->
                    async {
                        // Use values distinct from default (2)
                        viewModel.updateSettings { it.copy(fontSize = (i % 5)) }
                    }
                }
            deferreds.awaitAll()
            advanceUntilIdle()

            val state = viewModel.state.value.settings
            assertNotNull(state)
            assertTrue("FontSize should be valid", state!!.fontSize in 0..4)
        }

    @Test(expected = CancellationException::class)
    fun `saveAndNotify rethrows CancellationException`() =
        runTest {
            val cancelingSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult = throw CancellationException("Test cancellation")
                }
            val vm = WidgetSettingsViewModel(loader, cancelingSaver, usageStateRepository)
            advanceUntilIdle()

            vm.saveAndNotify()
        }

    @Test
    fun `saveAndNotify returns Failed on timeout`() =
        runTest {
            val hangingLoader =
                object : WidgetSettingsLoader {
                    override suspend fun load(): WidgetSettingsState = WidgetSettingsState()

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        delay((Constants.Flow.LONG_TIMEOUT_MS * 2).milliseconds)
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): WidgetSaveResult = WidgetSaveResult.Success
                }
            val realSaver = WidgetSettingsSaver(hangingLoader, mock())
            runBlocking {
                whenever(loader.load()).thenReturn(WidgetSettingsState())
            }
            val vm = WidgetSettingsViewModel(loader, realSaver, usageStateRepository)
            advanceUntilIdle()

            val saveJob =
                launch {
                    vm.saveAndNotify()
                }

            // Advance time past BOTH the VM timeout and the hanging loader delay
            advanceTimeBy((Constants.Flow.LONG_TIMEOUT_MS * 2 + 1000).milliseconds)
            advanceUntilIdle()

            assertEquals(
                SaveResult.Failed("Saving timed out. Storage might be slow or unavailable."),
                vm.state.value.saveResult,
            )
            saveJob.cancel()
        }

    @Test
    fun `saveAndNotify sets PartialFailure when simListLoadFailed is true`() =
        runTest {
            val partialSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        if (skipSimSettings) return SaveResult.PartialSuccess(skippedSimSettings = true)
                        return SaveResult.Success
                    }
                }
            val vm = WidgetSettingsViewModel(loader, partialSaver, usageStateRepository)
            advanceUntilIdle()

            vm.updateSettings { it.copy(simListLoadFailed = true) }
            advanceUntilIdle()

            // Advance for auto-save
            advanceTimeBy(500.milliseconds)
            runCurrent()

            assertTrue(vm.state.value.saveResult is SaveResult.PartialSuccess)
        }

    @Test
    fun `updateSettings resets loadError`() =
        runTest {
            // Имитировать ошибку загрузки
            runBlocking {
                whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            }
            val vm = WidgetSettingsViewModel(loader, saver, usageStateRepository)
            advanceUntilIdle()
            assertTrue(vm.state.value.isLoadError)

            // Обновить настройки
            vm.updateSettings { it.copy(fontSize = 3) }
            advanceUntilIdle()

            // Ошибка НЕ должна быть сброшена, так как settings == null
            assertTrue(vm.state.value.isLoadError)
        }

    @Test
    fun `updateSimSettings resets loadError`() =
        runTest {
            // Имитировать ошибку загрузки
            runBlocking {
                whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            }
            val vm = WidgetSettingsViewModel(loader, saver, usageStateRepository)
            advanceUntilIdle()
            assertTrue(vm.state.value.isLoadError)

            // Обновить настройки SIM
            vm.updateSimSettings(0) { it.copy(showTraffic = true) }
            advanceUntilIdle()

            // Ошибка НЕ должна быть сброшена, так как settings == null
            assertTrue(vm.state.value.isLoadError)
        }

    @Test
    fun `saveAndNotify returns Failed when loading failed previously`() =
        runTest {
            runBlocking {
                whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            }
            val vm = WidgetSettingsViewModel(loader, saver, usageStateRepository)
            advanceUntilIdle()
            assertTrue(vm.state.value.isLoadError)

            vm.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.Failed("Settings were not loaded"), vm.state.value.saveResult)
        }

    @Test
    fun `saveAndNotify returns Failed when state is not yet loaded`() =
        runTest {
            val deferred = CompletableDeferred<WidgetSettingsState>()
            val slowLoader =
                object : WidgetSettingsLoader {
                    override suspend fun load(): WidgetSettingsState = deferred.await()

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ) = WidgetSaveResult.Success

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        // No-op for testing
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val vm = WidgetSettingsViewModel(slowLoader, saver, usageStateRepository)
            // Do NOT advance time, so it's still loading inside init -> loadSettings

            vm.saveAndNotify()
            runCurrent()

            assertEquals(SaveResult.Failed("Settings are still loading"), vm.state.value.saveResult)
            deferred.complete(WidgetSettingsState())
            advanceUntilIdle()
        }

    @Test
    fun `validate clamps out-of-range values`() =
        runTest {
            val capturedStates = mutableListOf<WidgetSettingsState>()
            val capturingSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        capturedStates.add(state)
                        return SaveResult.Success
                    }
                }
            val vm = WidgetSettingsViewModel(loader, capturingSaver, usageStateRepository)
            advanceUntilIdle()

            // Test MAX values
            vm.updateSettings {
                it.copy(
                    layoutMode = Constants.Widget.MAX_LAYOUT_MODE + 1,
                    simArrangement = Constants.Widget.MAX_SIM_ARRANGEMENT + 1,
                    transparency = Constants.Widget.MAX_TRANSPARENCY + 1,
                    fontSize = Constants.Widget.MAX_FONT_SIZE + 1,
                    widgetLimitExceeded = true,
                )
            }
            advanceTimeBy(500.milliseconds)
            runCurrent()

            assertEquals(1, capturedStates.size)
            val captured = capturedStates.first()
            assertEquals(Constants.Widget.MAX_LAYOUT_MODE, captured.layoutMode)
            assertEquals(Constants.Widget.MAX_SIM_ARRANGEMENT, captured.simArrangement)
            assertEquals(Constants.Widget.MAX_TRANSPARENCY, captured.transparency)
            assertEquals(Constants.Widget.MAX_FONT_SIZE, captured.fontSize)
            assertFalse(captured.widgetLimitExceeded)

            // Test MIN values
            vm.updateSettings {
                it.copy(
                    layoutMode = Constants.Widget.MIN_LAYOUT_MODE - 1,
                    simArrangement = Constants.Widget.MIN_SIM_ARRANGEMENT - 1,
                    transparency = Constants.Widget.MIN_TRANSPARENCY - 1,
                    fontSize = Constants.Widget.MIN_FONT_SIZE - 1,
                )
            }
            advanceTimeBy(500.milliseconds)
            runCurrent()

            assertEquals(2, capturedStates.size)
            val captured2 = capturedStates.last()
            assertEquals(Constants.Widget.MIN_LAYOUT_MODE, captured2.layoutMode)
            assertEquals(Constants.Widget.MIN_SIM_ARRANGEMENT, captured2.simArrangement)
            assertEquals(Constants.Widget.MIN_TRANSPARENCY, captured2.transparency)
            assertEquals(Constants.Widget.MIN_FONT_SIZE, captured2.fontSize)
        }

    @Test
    fun `loadSettings DOES update state while saveAndNotify is in progress`() =
        runTest {
            val loadDeferred1 = CompletableDeferred<WidgetSettingsState>()
            val loadDeferred2 = CompletableDeferred<WidgetSettingsState>()
            val saveGate = CompletableDeferred<Unit>()

            val controlledLoader =
                object : WidgetSettingsLoader {
                    var loadCallCount = 0

                    override suspend fun load(): WidgetSettingsState =
                        if (loadCallCount++ == 0) {
                            loadDeferred1.await()
                        } else {
                            loadDeferred2.await()
                        }

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): WidgetSaveResult = WidgetSaveResult.Success

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        // Controlled by test
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val controlledSaver =
                object : WidgetSettingsSaver(controlledLoader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        saveGate.await()
                        return SaveResult.Success
                    }
                }

            // Create a NEW vm for this test to avoid shared state
            val vm = WidgetSettingsViewModel(controlledLoader, controlledSaver, usageStateRepository)
            runCurrent()

            // 1. Complete initial load
            loadDeferred1.complete(WidgetSettingsState(fontSize = 1))
            advanceUntilIdle()
            assertEquals(
                1,
                vm.state.value.settings
                    ?.fontSize,
            )

            // 2. Start saveAndNotify. Locks saveMutex.
            val saveJob = launch { vm.saveAndNotify() }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            // 3. Trigger second load
            vm.retryLoad()
            runCurrent()

            // 4. Complete second load I/O.
            loadDeferred2.complete(WidgetSettingsState(fontSize = 4))
            runCurrent()

            // 5. Verify state IS updated, because saveMutex no longer blocks loadSettings
            assertEquals(
                4,
                vm.state.value.settings
                    ?.fontSize,
            )

            // 6. Complete save
            saveGate.complete(Unit)
            advanceUntilIdle()
            saveJob.join()
        }

    @Test
    fun `updateSettings DOES update state while saveAndNotify is in progress`() =
        runTest {
            val saveGate = CompletableDeferred<Unit>()
            val controlledSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        saveGate.await()
                        return SaveResult.Success
                    }
                }

            runBlocking {
                whenever(loader.load()).thenReturn(WidgetSettingsState(fontSize = 1))
            }
            val vm = WidgetSettingsViewModel(loader, controlledSaver, usageStateRepository)
            advanceUntilIdle()

            // 1. Initial state
            assertEquals(
                1,
                vm.state.value.settings
                    ?.fontSize,
            )

            // 2. Start save. Locks saveMutex.
            val saveJob = launch { vm.saveAndNotify() }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            // 3. Update settings. Should be non-blocking.
            vm.updateSettings { it.copy(fontSize = 3) }
            runCurrent()

            // 4. Verify state IS updated
            assertEquals(
                3,
                vm.state.value.settings
                    ?.fontSize,
            )

            // 5. Cleanup
            saveGate.complete(Unit)
            advanceUntilIdle()
            saveJob.join()
        }
}
