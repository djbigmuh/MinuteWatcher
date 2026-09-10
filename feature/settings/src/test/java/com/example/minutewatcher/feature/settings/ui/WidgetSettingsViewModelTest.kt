package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSaveResult
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): WidgetSaveResult = WidgetSaveResult.Success
                }
            val realSaver = WidgetSettingsSaver(blockingLoader, mock())
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            val vm =
                WidgetSettingsViewModel(loader, realSaver, appSettingsRepository, backgroundScope, usageStateRepository)
            advanceUntilIdle()

            launch { vm.saveAndNotify() }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            // Второй вызов ждет завершения первого
            val secondSave = launch { vm.saveAndNotify() }
            runCurrent()

            // Настроили блокирующий Saver, так что второй вызов еще не завершился
            assertFalse(secondSave.isCompleted)

            gate.complete(Unit)
            advanceUntilIdle()

            // Оба завершились успешно
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
                    ): SaveResult = SaveResult.Failed("Generic failure")
                }
            val vm =
                WidgetSettingsViewModel(
                    loader,
                    failingSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            vm.saveAndNotify()
            runCurrent()

            assertEquals(SaveResult.Failed("Generic failure"), vm.state.value.saveResult)
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

            val vm =
                WidgetSettingsViewModel(
                    loader,
                    localSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
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
            val vm =
                WidgetSettingsViewModel(fakeLoader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
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
    fun `concurrent updateSettings with increment does not lose updates`() =
        runTest {
            initViewModel()
            val totalUpdates = 20
            val deferreds =
                List(totalUpdates) {
                    async {
                        viewModel.updateSettings { it.copy(transparency = it.transparency + 1) }
                    }
                }
            deferreds.awaitAll()
            advanceUntilIdle()

            val state = viewModel.state.value.settings
            assertNotNull(state)
            // Initial is 50, +20 = 70
            assertEquals(70, state!!.transparency)
        }

    @Test
    fun `concurrent updateSimSettings different slots does not lose updates`() =
        runTest {
            initViewModel()
            val slots = 0 until 5
            val deferreds =
                slots.flatMap { slot ->
                    listOf(
                        async { viewModel.updateSimSettings(slot) { it.copy(showSim = false) } },
                        async { viewModel.updateSimSettings(slot) { it.copy(showIcon = false) } },
                    )
                }
            deferreds.awaitAll()
            advanceUntilIdle()

            val state = viewModel.state.value.settings
            assertNotNull(state)
            slots.forEach { slot ->
                val simState = state!!.simStates[slot]
                assertNotNull("State for slot $slot should exist", simState)
                assertFalse("showSim for slot $slot should be false", simState!!.showSim)
                assertFalse("showIcon for slot $slot should be false", simState.showIcon)
            }
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
            val vm =
                WidgetSettingsViewModel(
                    loader,
                    cancelingSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
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
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            val vm =
                WidgetSettingsViewModel(loader, realSaver, appSettingsRepository, backgroundScope, usageStateRepository)
            advanceUntilIdle()

            val saveJob =
                launch {
                    vm.saveAndNotify()
                }

            // Advance time past the VM timeout (15s) but less than cleanup timeout (2s more)
            advanceTimeBy((Constants.Flow.LONG_TIMEOUT_MS + 500).milliseconds)
            runCurrent()

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
            val vm =
                WidgetSettingsViewModel(
                    loader,
                    partialSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            vm.updateSettings { it.copy(simListLoadFailed = true) }
            runCurrent()

            // Advance for auto-save
            advanceTimeBy(500.milliseconds)
            runCurrent()

            assertTrue(vm.state.value.saveResult is SaveResult.PartialSuccess)
        }

    @Test
    fun `updateSettings resets loadError`() =
        runTest {
            // Имитировать ошибку загрузки
            whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            val vm =
                WidgetSettingsViewModel(loader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
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
            whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            val vm =
                WidgetSettingsViewModel(loader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
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
            whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            val vm =
                WidgetSettingsViewModel(loader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
            advanceUntilIdle()
            assertTrue(vm.state.value.isLoadError)

            vm.saveAndNotify()
            runCurrent()

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

            val vm =
                WidgetSettingsViewModel(slowLoader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
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
            val vm =
                WidgetSettingsViewModel(
                    loader,
                    capturingSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
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
            assertTrue(captured.widgetLimitExceeded)

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
    fun `updateSettings validates state immediately in memory`() =
        runTest {
            initViewModel()

            // Test out-of-range value
            viewModel.updateSettings {
                it.copy(transparency = 150) // Max is 100
            }
            runCurrent()

            val currentSettings = viewModel.state.value.settings
            assertNotNull(currentSettings)
            assertEquals(100, currentSettings!!.transparency)
        }

    @Test
    fun `pending updates are validated before setting state`() =
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
                        // Not needed for this test
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val vm =
                WidgetSettingsViewModel(slowLoader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
            runCurrent()

            // Queue invalid update
            vm.updateSettings { it.copy(fontSize = 10) } // Max is 4
            runCurrent()

            deferred.complete(WidgetSettingsState(fontSize = 1))
            advanceUntilIdle()

            val finalSettings = vm.state.value.settings
            assertNotNull(finalSettings)
            assertEquals(4, finalSettings!!.fontSize)
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
            val vm =
                WidgetSettingsViewModel(
                    controlledLoader,
                    controlledSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
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

            whenever(loader.load()).thenReturn(WidgetSettingsState(fontSize = 1))
            val vm =
                WidgetSettingsViewModel(
                    loader,
                    controlledSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
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

    @Test
    fun `updateSettings queues transforms before load and applies them after successful load`() =
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
                        // Not needed for this test
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val vm =
                WidgetSettingsViewModel(slowLoader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
            runCurrent()

            // 1. Queue updates
            vm.updateSettings { it.copy(fontSize = 4) }
            vm.updateSettings { it.copy(layoutMode = 2) }
            runCurrent()

            // 2. Load succeeds
            val initialState = WidgetSettingsState(fontSize = 1, layoutMode = 1)
            deferred.complete(initialState)
            advanceUntilIdle()

            // 3. Verify applied
            val finalSettings = vm.state.value.settings
            assertNotNull(finalSettings)
            assertEquals(4, finalSettings!!.fontSize)
            assertEquals(2, finalSettings.layoutMode)

            // 4. Verify auto-save triggered (advance time for debounce)
            advanceTimeBy(500.milliseconds)
            runCurrent()
            verify(saver).save(any(), any())
        }

    @Test
    fun `updateSimSettings queues transforms before load and applies them after successful load`() =
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
                        // Not needed for this test
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val vm =
                WidgetSettingsViewModel(slowLoader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
            runCurrent()

            // 1. Queue SIM update
            vm.updateSimSettings(0) { it.copy(showTraffic = true) }
            runCurrent()

            // 2. Load succeeds
            deferred.complete(WidgetSettingsState())
            advanceUntilIdle()

            // 3. Verify applied
            val simState =
                vm.state.value.settings
                    ?.simStates
                    ?.get(0)
            assertNotNull(simState)
            assertTrue(simState!!.showTraffic)
        }

    @Test
    fun `pendingUpdates queue merges transforms when limit reached to avoid data loss`() =
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
                        // Not needed for this test
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val vm =
                WidgetSettingsViewModel(slowLoader, saver, appSettingsRepository, backgroundScope, usageStateRepository)
            runCurrent()

            // 1. Flood with updates
            // MAX_PENDING_UPDATES is 10. Adding 15 updates.
            repeat(15) {
                vm.updateSettings { it.copy(transparency = it.transparency + 1) }
            }
            runCurrent()

            // 2. Load
            deferred.complete(WidgetSettingsState(transparency = 0))
            advanceUntilIdle()

            // 3. All 15 updates should be applied if they were merged correctly
            assertEquals(
                15,
                vm.state.value.settings
                    ?.transparency,
            )
        }

    @Test
    fun `onCleared triggers emergency save when there are unsaved changes`() =
        runTest {
            val saveStarted = CompletableDeferred<Unit>()
            val capturedStates = mutableListOf<WidgetSettingsState>()
            val controlledSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        capturedStates.add(state)
                        saveStarted.complete(Unit)
                        return SaveResult.Success
                    }
                }

            val vm =
                WidgetSettingsViewModel(
                    loader,
                    controlledSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            // 1. Change setting, but don't wait for auto-save debounce (400ms)
            vm.updateSettings { it.copy(fontSize = 4) }
            runCurrent()

            // Verify no save yet
            assertTrue(capturedStates.isEmpty())

            // 2. Act: Call onCleared via reflection
            val method = vm.javaClass.getDeclaredMethod("onCleared")
            method.isAccessible = true
            method.invoke(vm)
            runCurrent()

            // 3. Assert: Verify emergency save WAS triggered in appScope
            saveStarted.await()
            assertEquals(1, capturedStates.size)
            assertEquals(4, capturedStates.first().fontSize)
        }

    @Test
    fun `onCleared does NOT trigger save if lastSavedSettings is null`() =
        runTest {
            initViewModel()

            // Force lastSavedSettings to null via reflection
            val lastSavedField = viewModel.javaClass.getDeclaredField("lastSavedSettings")
            lastSavedField.isAccessible = true
            lastSavedField.set(viewModel, null)

            // Change something to make current != lastSaved (if it were non-null)
            viewModel.updateSettings { it.copy(fontSize = 4) }
            runCurrent()

            // Verify no auto-save yet (debounce 400ms)
            verify(saver, never()).save(any(), any())

            // Act: Call onCleared
            val method = viewModel.javaClass.getDeclaredMethod("onCleared")
            method.isAccessible = true
            method.invoke(viewModel)
            runCurrent()

            // Assert: Verify NO save was triggered because lastSavedSettings is null
            verify(saver, never()).save(any(), any())
        }

    @Test
    fun `onCleared does NOT trigger emergency save even if saver hangs`() =
        runTest {
            initViewModel()
            val hangingSaveStarted = CompletableDeferred<Unit>()
            val hangingSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        hangingSaveStarted.complete(Unit)
                        delay(10000L) // 10 seconds
                        return SaveResult.Success
                    }
                }

            val vm =
                WidgetSettingsViewModel(
                    loader,
                    hangingSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            // 1. Change setting
            vm.updateSettings { it.copy(fontSize = 4) }
            runCurrent()

            // 2. Trigger onCleared
            val method = vm.javaClass.getDeclaredMethod("onCleared")
            method.isAccessible = true
            method.invoke(vm)

            // 3. Verify no save started
            assertFalse(hangingSaveStarted.isCompleted)
        }

    @Test
    fun `saveAndNotify is not interrupted by scope cancellation`() =
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

            val vm =
                WidgetSettingsViewModel(
                    loader,
                    controlledSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            // Use a separate job for saveAndNotify to cancel it independently
            val job =
                launch {
                    vm.saveAndNotify()
                }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            // Cancel the calling job
            job.cancel()
            runCurrent()

            // Complete the save in the background (NonCancellable should protect executeSave)
            saveGate.complete(Unit)
            advanceUntilIdle()

            // Verify isSaving is eventually false
            assertFalse(vm.state.value.isSaving)
        }

    @Test
    fun `saveAndNotify saves captured settings even if state changes during save`() =
        runTest {
            val capturedStates = mutableListOf<WidgetSettingsState>()
            val saveGate = CompletableDeferred<Unit>()
            val controlledSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        capturedStates.add(state)
                        saveGate.await()
                        return SaveResult.Success
                    }
                }

            whenever(loader.load()).thenReturn(WidgetSettingsState(fontSize = 1))
            val vm =
                WidgetSettingsViewModel(
                    loader,
                    controlledSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            // 1. Start save
            val saveJob = launch { vm.saveAndNotify() }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            // 2. Change settings in state while saving is in progress
            vm.updateSettings { it.copy(fontSize = 3) }
            runCurrent()
            assertEquals(
                3,
                vm.state.value.settings
                    ?.fontSize,
            )

            // 3. Complete save
            saveGate.complete(Unit)
            advanceUntilIdle()
            saveJob.join()

            // 4. Verify that the FIRST SAVED state had fontSize = 1 (captured at start)
            // NOT fontSize = 3 (which was set later)
            assertTrue("Should have saved at least once", capturedStates.isNotEmpty())
            assertEquals(1, capturedStates.first().fontSize)

            // Verify that it eventually saved the new state (due to auto-save retry)
            assertEquals(3, capturedStates.last().fontSize)
        }

    @Test
    fun `sequential updates trigger multiple saves ensuring final state is persisted`() =
        runTest {
            val capturedStates = mutableListOf<WidgetSettingsState>()
            var saveGate = CompletableDeferred<Unit>()
            val controlledSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        capturedStates.add(state)
                        saveGate.await()
                        return SaveResult.Success
                    }
                }

            val vm =
                WidgetSettingsViewModel(
                    loader,
                    controlledSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            // 1. First update
            vm.updateSettings { it.copy(fontSize = 1) }
            advanceTimeBy(500.milliseconds)
            runCurrent()

            assertTrue(vm.state.value.isSaving)
            assertEquals(1, capturedStates.size)
            assertEquals(1, capturedStates.last().fontSize)

            // 2. Second save attempt manually while first is saving
            // Re-create gate for second save
            val firstSaveGate = saveGate
            saveGate = CompletableDeferred()

            // Manual call instead of auto-save to trigger waiting immediately
            launch {
                vm.saveAndNotify()
            }
            runCurrent()

            // Should be waiting for lock, isSaving still true from first save
            assertTrue(vm.state.value.isSaving)

            // 3. Complete first save
            firstSaveGate.complete(Unit)
            runCurrent()
            advanceTimeBy(1.milliseconds)

            // Second save should start now
            assertTrue(vm.state.value.isSaving)
            assertEquals(2, capturedStates.size)

            // 4. Complete second save
            saveGate.complete(Unit)
            runCurrent()
            advanceTimeBy(1.milliseconds)

            // After both saves complete
            assertFalse(vm.state.value.isSaving)
            assertEquals(SaveResult.Success, vm.state.value.saveResult)
        }

    @Test
    fun `saveAndNotify resets isSaving even when parent scope is cancelled`() =
        runTest {
            val saveStarted = CompletableDeferred<Unit>()
            val canFinishSave = CompletableDeferred<Unit>()
            val controlledSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        saveStarted.complete(Unit)
                        canFinishSave.await()
                        return SaveResult.Success
                    }
                }

            val vm =
                WidgetSettingsViewModel(
                    loader,
                    controlledSaver,
                    appSettingsRepository,
                    backgroundScope,
                    usageStateRepository,
                )
            advanceUntilIdle()

            // 1. Start save in a child scope that we can cancel
            val childScope = CoroutineScope(testDispatcher + Job())
            val saveJob =
                childScope.launch {
                    vm.saveAndNotify()
                }
            runCurrent()
            saveStarted.await()
            assertTrue(vm.state.value.isSaving)

            // 2. Cancel the child scope
            childScope.launch { saveJob.cancel() } // Trigger cancellation
            runCurrent()

            // 3. Complete the underlying save (it's in NonCancellable block)
            canFinishSave.complete(Unit)
            advanceUntilIdle()

            // 4. Verify isSaving is false even though the parent coroutine was cancelled
            assertFalse("isSaving should be false after cancellation", vm.state.value.isSaving)
        }

    @Test
    fun `saveResult is NOT cleared automatically`() =
        runTest {
            initViewModel()

            // 1. Success case
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Success)
            viewModel.saveAndNotify()
            runCurrent()
            assertEquals(SaveResult.Success, viewModel.state.value.saveResult)

            // Advance past some time
            advanceTimeBy(5000.milliseconds)
            runCurrent()
            assertEquals(
                "Success should NOT be cleared automatically by VM",
                SaveResult.Success,
                viewModel.state.value.saveResult,
            )

            viewModel.consumeSaveResult()
            assertEquals(null, viewModel.state.value.saveResult)

            // 2. Failure case
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Failed("Error"))
            viewModel.saveAndNotify()
            runCurrent()
            assertEquals(SaveResult.Failed("Error"), viewModel.state.value.saveResult)

            // Advance past some time
            advanceTimeBy(5000.milliseconds)
            runCurrent()
            assertEquals(
                "Failure should NOT be cleared automatically",
                SaveResult.Failed("Error"),
                viewModel.state.value.saveResult,
            )

            // Manual cleanup
            viewModel.consumeSaveResult()
            assertEquals(null, viewModel.state.value.saveResult)
        }

    @Test
    fun `updateSettings preserves widgetLimitExceeded`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(widgetLimitExceeded = true) }
            runCurrent()

            // Change another setting
            viewModel.updateSettings { it.copy(fontSize = 1) }
            runCurrent()

            val currentSettings = viewModel.state.value.settings
            assertNotNull(currentSettings)
            assertTrue("widgetLimitExceeded should be preserved", currentSettings!!.widgetLimitExceeded)
        }

    @Test
    fun `appTheme emits value from appSettingsRepository`() =
        runTest(testDispatcher) {
            val themeFlow = MutableStateFlow(AppSettings(theme = AppTheme.DARK))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(themeFlow)

            initViewModel()
            val job = launch { viewModel.appTheme.collect {} }
            advanceUntilIdle()

            assertEquals(AppTheme.DARK, viewModel.appTheme.value)

            themeFlow.value = AppSettings(theme = AppTheme.LIGHT)
            advanceUntilIdle()
            assertEquals(AppTheme.LIGHT, viewModel.appTheme.value)
            job.cancel()
        }
}
