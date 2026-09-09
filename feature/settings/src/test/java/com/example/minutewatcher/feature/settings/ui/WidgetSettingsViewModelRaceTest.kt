package com.example.minutewatcher.feature.settings.ui

import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelRaceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loader: WidgetSettingsLoader = mock()
    private val saver: WidgetSettingsSaver = mock()
    private val usageStateRepository: UsageStateRepository = mock()

    private lateinit var viewModel: WidgetSettingsViewModel

    @Before
    fun setup() =
        runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
            val snapshotFlow = MutableStateFlow(snapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(loader.load()).thenReturn(WidgetSettingsState(fontSize = 4))

            viewModel = WidgetSettingsViewModel(loader, saver, backgroundScope, usageStateRepository)
            advanceUntilIdle()
        }

    @After
    fun tearDown() {
        // MainDispatcherRule handles resetMain()
    }

    @Test
    fun `saveAndNotify persists updates made during the lock acquisition`() =
        runTest {
            val saveStarted = CompletableDeferred<Unit>()
            val canCompleteSave = CompletableDeferred<Unit>()
            val capturedStates = mutableListOf<WidgetSettingsState>()

            val controlledSaver =
                object : WidgetSettingsSaver(loader, mock()) {
                    override suspend fun save(
                        state: WidgetSettingsState,
                        skipSimSettings: Boolean,
                    ): SaveResult {
                        capturedStates.add(state)
                        saveStarted.complete(Unit)
                        canCompleteSave.await()
                        return SaveResult.Success
                    }
                }

            val vm = WidgetSettingsViewModel(loader, controlledSaver, backgroundScope, usageStateRepository)
            advanceUntilIdle()

            // 1. Start save operation
            val saveJob =
                launch {
                    vm.saveAndNotify()
                }
            runCurrent()
            saveStarted.await()

            // 2. Try to update settings while save is in progress
            // This update is NOT blocked by saveMutex in memory,
            // but the RESULTING auto-save will be blocked.
            vm.updateSettings { it.copy(fontSize = 2) }
            runCurrent()

            // 3. Allow first save to complete
            canCompleteSave.complete(Unit)
            saveJob.join()

            // 4. Verify that the FIRST save call captured the state BEFORE the update was applied
            assertEquals("First save should see old state", 4, capturedStates.first().fontSize)

            // 5. Trigger second save to verify the update was NOT lost and is now in memory
            vm.saveAndNotify()
            runCurrent()

            assertEquals("Second save should see updated state", 2, capturedStates.last().fontSize)
            assertEquals("Total saves should be 2", 2, capturedStates.size)
        }
}
