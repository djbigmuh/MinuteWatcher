package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
            val snapshotFlow: StateFlow<GlobalSnapshot> = MutableStateFlow<GlobalSnapshot>(snapshot)
            whenever<StateFlow<GlobalSnapshot>>(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            whenever(saver.save(any())).thenReturn(true)

            viewModel = WidgetSettingsViewModel(context, loader, saver, usageStateRepository)
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
            advanceUntilIdle()

            // Start first save
            viewModel.saveAndNotify { }

            // Check that isSaving is true immediately
            assertTrue(viewModel.isSaving.value)

            // Attempt second save immediately
            var secondCompleted = false
            viewModel.saveAndNotify { secondCompleted = true }

            advanceUntilIdle()

            // Second save should NOT have run/completed
            assertFalse(secondCompleted)

            // Verify save was called only once
            verify(saver).save(any())
            assertFalse(viewModel.isSaving.value)
        }

    @Test
    fun `updateSettings updates state locally`() =
        runTest {
            advanceUntilIdle()
            viewModel.updateSettings { it.copy(fontSize = 10) }
            assertEquals(10, viewModel.settingsState.value?.fontSize)
        }
}
