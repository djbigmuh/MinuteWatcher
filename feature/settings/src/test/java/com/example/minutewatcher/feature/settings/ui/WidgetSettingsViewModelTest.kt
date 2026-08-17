package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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
            whenever(saver.save(any(), any())).thenReturn(true)

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
            whenever(saver.save(any(), any())).thenReturn(true)
            testScheduler.advanceUntilIdle()
            val result = viewModel.saveAndNotify()
            testScheduler.advanceUntilIdle()

            assertEquals(SaveResult.Success, result)
            verify(saver).save(any(), any())
        }

    @Test
    fun `saveAndNotify prevents concurrent saves`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val blockingLoader =
                object : WidgetSettingsLoader {
                    override suspend fun load(): WidgetSettingsState = WidgetSettingsState()

                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ) {
                        gate.await()
                    }
                }
            val realSaver = WidgetSettingsSaver(blockingLoader, mock())
            val vm = WidgetSettingsViewModel(loader, realSaver, usageStateRepository)
            testScheduler.advanceUntilIdle()

            var firstResult: SaveResult? = null
            val secondResult: SaveResult?

            launch { firstResult = vm.saveAndNotify() }
            testScheduler.runCurrent()
            assertTrue(vm.isSaving.value)

            secondResult = vm.saveAndNotify()
            // В новом варианте второй вызов возвращает отказ сразу, так как сохранение уже в процессе
            assertEquals(SaveResult.AlreadyInProgress, secondResult)

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(SaveResult.Success, firstResult)
            assertFalse(vm.isSaving.value)
        }

    @Test
    fun `saveAndNotify returns Failed on exception`() =
        runTest {
            whenever(saver.save(any(), any())).thenThrow(RuntimeException("Save failed"))
            testScheduler.advanceUntilIdle()

            val result = viewModel.saveAndNotify()
            testScheduler.advanceUntilIdle()

            assertEquals(SaveResult.Failed, result)
        }

    @Test
    fun `updateSettings updates state locally`() =
        runTest {
            testScheduler.advanceUntilIdle()
            viewModel.updateSettings { it.copy(fontSize = 10) }
            assertEquals(10, viewModel.settingsState.value?.fontSize)
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
                    ) {
                        // Empty for mock
                    }
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
            assertEquals(20, vm.settingsState.value?.fontSize)
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

            val finalFontSize = viewModel.settingsState.value?.fontSize
            assertNotNull(finalFontSize)
            assertTrue(
                "FontSize should be one of the updated values and not default",
                finalFontSize!! in 100 until (100 + totalUpdates),
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
    fun `saveAndNotify returns Success with skipSimSettings when simListLoadFailed is true`() =
        runTest {
            testScheduler.advanceUntilIdle()
            viewModel.updateSettings { it.copy(simListLoadFailed = true) }

            val result = viewModel.saveAndNotify()

            assertEquals(SaveResult.PartialSuccess(skippedSimSettings = true), result)
            verify(saver).save(any(), eq(true))
        }
}
