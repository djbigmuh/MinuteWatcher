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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    private var saveDeferred = CompletableDeferred<Boolean>()

    private lateinit var viewModel: WidgetSettingsViewModel

    @Before
    fun setup() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            saveDeferred = CompletableDeferred()
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
            val snapshotFlow: StateFlow<GlobalSnapshot> = MutableStateFlow<GlobalSnapshot>(snapshot)
            whenever<StateFlow<GlobalSnapshot>>(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
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
            advanceUntilIdle()
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
            advanceUntilIdle()
            assertTrue(initialViewModel.loadError.value)

            initialViewModel.retryLoad()
            advanceUntilIdle()
            assertFalse(initialViewModel.loadError.value)
            assertNotNull(initialViewModel.settingsState.value)
        }

    @Test
    fun `saveAndNotify calls save and notifies`() =
        runTest {
            whenever(saver.save(any())).thenReturn(true)
            advanceUntilIdle()
            var completed = false
            viewModel.saveAndNotify { success ->
                completed = true
                assertTrue(success)
            }
            advanceUntilIdle()

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
            advanceUntilIdle()

            var firstResult: Boolean? = null
            var secondCompleted = false

            vm.saveAndNotify { firstResult = it }
            runCurrent()
            assertTrue(vm.isSaving.value)

            vm.saveAndNotify { secondCompleted = true }
            runCurrent()
            assertFalse(secondCompleted) // второй вызов отклонён tryLock

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(true, firstResult)
            assertFalse(vm.isSaving.value)
        }

    @Test
    fun `updateSettings updates state locally`() =
        runTest {
            advanceUntilIdle()
            viewModel.updateSettings { it.copy(fontSize = 10) }
            assertEquals(10, viewModel.settingsState.value?.fontSize)
        }
}
