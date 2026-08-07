package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
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
            whenever(saver.save(any())).thenReturn(true)

            viewModel = WidgetSettingsViewModel(context, loader, saver, usageStateRepository)
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
            val initialViewModel = WidgetSettingsViewModel(context, loader, saver, usageStateRepository)
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
            whenever(saver.save(any())).thenReturn(true)
            testScheduler.advanceUntilIdle()
            var completed = false
            viewModel.saveAndNotify { success ->
                completed = true
                assertTrue(success)
            }
            testScheduler.advanceUntilIdle()

            assertTrue(completed)
            verify(saver).save(any())
        }

    @Test
    fun `saveAndNotify prevents concurrent saves`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val blockingLoader =
                object : WidgetSettingsLoader {
                    override suspend fun load(): WidgetSettingsState = WidgetSettingsState()

                    override suspend fun save(state: WidgetSettingsState) {
                        gate.await()
                    }
                }
            val blockingSaver = WidgetSettingsSaver(blockingLoader, mock())
            val vm = WidgetSettingsViewModel(context, loader, blockingSaver, usageStateRepository)
            testScheduler.advanceUntilIdle()

            var firstResult: Boolean? = null
            var secondResult: Boolean? = null

            vm.saveAndNotify { firstResult = it }
            runCurrent()
            assertTrue(vm.isSaving.value)

            vm.saveAndNotify { secondResult = it }
            runCurrent()
            assertEquals(false, secondResult) // второй вызов отклонён tryLock и вернул false

            gate.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(true, firstResult)
            assertFalse(vm.isSaving.value)
        }

    @Test
    fun `updateSettings updates state locally`() =
        runTest {
            testScheduler.advanceUntilIdle()
            viewModel.updateSettings { it.copy(fontSize = 10) }
            assertEquals(10, viewModel.settingsState.value?.fontSize)
        }
}
