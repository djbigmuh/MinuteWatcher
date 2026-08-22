package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSaveResult
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelTest {
    private val loader: WidgetSettingsLoader = mock()
    private val saver: WidgetSettingsSaver = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private lateinit var viewModel: WidgetSettingsViewModel

    @Before
    fun setup() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
            val snapshotFlow = MutableStateFlow(snapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            // Default: successful immediate save
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Success)

            viewModel = WidgetSettingsViewModel(loader, saver, usageStateRepository)
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads settings`() =
        runTest {
            testScheduler.advanceUntilIdle()
            assertNotNull(viewModel.settingsState.value)
            assertFalse(viewModel.loadError.value)
            verify(loader).load()
        }

    @Test
    fun `retryLoad re-attempts loading`() =
        runTest {
            whenever(loader.load()).thenThrow(RuntimeException("Fail")).thenReturn(WidgetSettingsState())

            // Initial load in init
            val initialViewModel = WidgetSettingsViewModel(loader, saver, usageStateRepository)
            testScheduler.advanceUntilIdle()
            assertTrue(initialViewModel.loadError.value)

            initialViewModel.retryLoad()
            testScheduler.advanceUntilIdle()
            assertFalse(initialViewModel.loadError.value)
            assertNotNull(initialViewModel.settingsState.value)
        }

    @Test
    fun `saveAndNotify calls save and notifies`() =
        runTest {
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Success)
            testScheduler.advanceUntilIdle()
            viewModel.saveAndNotify()
            testScheduler.advanceUntilIdle()

            assertEquals(SaveResult.Success, viewModel.saveResult.value)
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
            val vm = WidgetSettingsViewModel(loader, realSaver, usageStateRepository)
            testScheduler.advanceUntilIdle()

            launch { vm.saveAndNotify() }
            testScheduler.runCurrent()
            assertTrue(vm.isSaving.value)

            vm.saveAndNotify()
            // В новом варианте второй вызов записывает AlreadyInProgress в StateFlow
            assertEquals(SaveResult.AlreadyInProgress, vm.saveResult.value)

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(SaveResult.Success, vm.saveResult.value)
            assertFalse(vm.isSaving.value)
        }

    @Test
    fun `saveAndNotify sets Failed on exception`() =
        runTest {
            whenever(saver.save(any(), any())).thenAnswer { SaveResult.Failed }
            testScheduler.advanceUntilIdle()

            viewModel.saveAndNotify()
            testScheduler.advanceUntilIdle()

            assertEquals(SaveResult.Failed, viewModel.saveResult.value)
        }

    @Test
    fun `updateSettings updates state locally`() =
        runTest {
            testScheduler.advanceUntilIdle()
            viewModel.updateSettings { it.copy(fontSize = 10) }
            val state = viewModel.settingsState.value
            assertNotNull(state)
            assertEquals(10, state!!.fontSize)
        }

    @Test
    fun `retryLoad cancels previous load job`() =
        runTest {
            val firstLoadDeferred = CompletableDeferred<WidgetSettingsState>()
            val secondLoadState = WidgetSettingsState(fontSize = 20)

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
            firstLoadDeferred.complete(WidgetSettingsState(fontSize = 10))
            testScheduler.advanceUntilIdle()

            // 4. Assert state contains result from second load
            val state = vm.settingsState.value
            assertNotNull(state)
            assertEquals(20, state!!.fontSize)
            assertEquals(2, fakeLoader.callCount)
        }

    @Test
    fun `concurrent updateSettings does not lose updates`() =
        runTest {
            testScheduler.advanceUntilIdle()
            val totalUpdates = 10
            val deferreds =
                List(totalUpdates) { i ->
                    async {
                        // Use values distinct from default (2)
                        viewModel.updateSettings { it.copy(fontSize = i + 100) }
                    }
                }
            deferreds.awaitAll()

            val state = viewModel.settingsState.value
            assertNotNull(state)
            val finalFontSize = state!!.fontSize
            assertTrue(
                "FontSize should be one of the updated values and not default",
                finalFontSize in 100 until (100 + totalUpdates),
            )
        }

    @Test(expected = CancellationException::class)
    fun `saveAndNotify rethrows CancellationException`() =
        runTest {
            whenever(saver.save(any(), any())).thenThrow(CancellationException("Test cancellation"))
            testScheduler.advanceUntilIdle()

            viewModel.saveAndNotify()
        }

    @Test
    fun `saveAndNotify returns Failed on timeout`() =
        runTest {
            val hangingLoader =
                object : WidgetSettingsLoader {
                    override suspend fun load(): WidgetSettingsState = WidgetSettingsState()

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        delay(Constants.Flow.LONG_TIMEOUT_MS * 2)
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): WidgetSaveResult = WidgetSaveResult.Success
                }
            val realSaver = WidgetSettingsSaver(hangingLoader, mock())
            val vm = WidgetSettingsViewModel(loader, realSaver, usageStateRepository)
            testScheduler.advanceUntilIdle()

            val job =
                launch {
                    vm.saveAndNotify()
                }

            // Advance time just past the timeout
            testScheduler.advanceTimeBy(Constants.Flow.LONG_TIMEOUT_MS + 1000)
            runCurrent()

            assertEquals(SaveResult.Failed, vm.saveResult.value)
            job.cancel()
        }

    @Test
    fun `saveAndNotify sets PartialFailure when simListLoadFailed is true`() =
        runTest {
            whenever(saver.save(any(), eq(true))).thenReturn(SaveResult.PartialSuccess(skippedSimSettings = true))
            testScheduler.advanceUntilIdle()
            viewModel.updateSettings { it.copy(simListLoadFailed = true) }

            viewModel.saveAndNotify()
            testScheduler.advanceUntilIdle()

            // Result depends on how PartialSuccess is handled in VM.
            // In the current implementation it is passed as is.
            assertTrue(viewModel.saveResult.value is SaveResult.PartialSuccess)
            verify(saver).save(any(), eq(true))
        }

    @Test
    fun `updateSettings resets loadError`() =
        runTest {
            // Имитировать ошибку загрузки
            whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            val vm = WidgetSettingsViewModel(loader, saver, usageStateRepository)
            testScheduler.advanceUntilIdle()
            assertTrue(vm.loadError.value)

            // Обновить настройки
            vm.updateSettings { it.copy(fontSize = 10) }

            // Ошибка должна быть сброшена
            assertFalse(vm.loadError.value)
        }

    @Test
    fun `updateSimSettings resets loadError`() =
        runTest {
            // Имитировать ошибку загрузки
            whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            val vm = WidgetSettingsViewModel(loader, saver, usageStateRepository)
            testScheduler.advanceUntilIdle()
            assertTrue(vm.loadError.value)

            // Обновить настройки SIM
            vm.updateSimSettings(0) { it.copy(showTraffic = true) }

            assertFalse(vm.loadError.value)
        }

    @Test
    fun `saveAndNotify returns Failed when loading failed previously`() =
        runTest {
            whenever(loader.load()).thenThrow(RuntimeException("Fail"))
            val vm = WidgetSettingsViewModel(loader, saver, usageStateRepository)
            testScheduler.advanceUntilIdle()
            assertTrue(vm.loadError.value)

            vm.saveAndNotify()
            testScheduler.advanceUntilIdle()

            assertEquals(SaveResult.Failed, vm.saveResult.value)
            verify(saver, never()).save(any(), any())
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
                    ) = com.example.minutewatcher.core.domain.WidgetSaveResult.Success

                    override suspend fun saveGeneralSettings(state: WidgetSettingsState) {
                        // No-op for testing
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }

            val vm = WidgetSettingsViewModel(slowLoader, saver, usageStateRepository)
            // Do NOT advance time, so it's still loading inside init -> loadSettings

            vm.saveAndNotify()
            testScheduler.runCurrent()

            assertEquals(SaveResult.Failed, vm.saveResult.value)
            verify(saver, never()).save(any(), any())
            deferred.complete(WidgetSettingsState())
        }

    @Test
    fun `validate clamps out-of-range values`() =
        runTest {
            testScheduler.advanceUntilIdle()

            // Test MAX values
            viewModel.updateSettings {
                it.copy(
                    layoutMode = Constants.Widget.MAX_LAYOUT_MODE + 1,
                    simArrangement = Constants.Widget.MAX_SIM_ARRANGEMENT + 1,
                    transparency = Constants.Widget.MAX_TRANSPARENCY + 1,
                    fontSize = Constants.Widget.MAX_FONT_SIZE + 1,
                    widgetLimitExceeded = true,
                )
            }

            viewModel.saveAndNotify()
            testScheduler.advanceUntilIdle()

            argumentCaptor<WidgetSettingsState>().apply {
                verify(saver).save(capture(), any())
                val captured = firstValue
                assertEquals(Constants.Widget.MAX_LAYOUT_MODE, captured.layoutMode)
                assertEquals(Constants.Widget.MAX_SIM_ARRANGEMENT, captured.simArrangement)
                assertEquals(Constants.Widget.MAX_TRANSPARENCY, captured.transparency)
                assertEquals(Constants.Widget.MAX_FONT_SIZE, captured.fontSize)
                assertFalse(captured.widgetLimitExceeded)
            }

            // Test MIN values
            viewModel.updateSettings {
                it.copy(
                    layoutMode = Constants.Widget.MIN_LAYOUT_MODE - 1,
                    simArrangement = Constants.Widget.MIN_SIM_ARRANGEMENT - 1,
                    transparency = Constants.Widget.MIN_TRANSPARENCY - 1,
                    fontSize = Constants.Widget.MIN_FONT_SIZE - 1,
                )
            }

            viewModel.saveAndNotify()
            testScheduler.advanceUntilIdle()

            argumentCaptor<WidgetSettingsState>().apply {
                verify(saver, times(2)).save(capture(), any())
                val captured = lastValue
                assertEquals(Constants.Widget.MIN_LAYOUT_MODE, captured.layoutMode)
                assertEquals(Constants.Widget.MIN_SIM_ARRANGEMENT, captured.simArrangement)
                assertEquals(Constants.Widget.MIN_TRANSPARENCY, captured.transparency)
                assertEquals(Constants.Widget.MIN_FONT_SIZE, captured.fontSize)
            }
        }
}
