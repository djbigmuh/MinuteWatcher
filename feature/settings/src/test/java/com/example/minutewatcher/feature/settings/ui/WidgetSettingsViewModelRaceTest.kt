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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
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

            viewModel = WidgetSettingsViewModel(loader, saver, usageStateRepository)
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

            whenever(saver.save(any(), any())).thenAnswer {
                saveStarted.complete(Unit)
                // Use a proper suspendable mock if possible, or wait deterministically.
                // Since this is a Mockito answer for a suspend function, we can't easily
                // return a value that suspends without runBlocking or making the answer itself suspend.
                // In modern mockito-kotlin, answers for suspend functions ARE executed in coroutine.
                // But the Return type of the lambda must match the Return type of the function.

                // If we use runBlocking here, we might block the test thread if using Single threaded dispatcher.
                // However, StandardTestDispatcher IS single threaded (usually).

                // To avoid runBlocking, we can use a custom Answer or just accept it if it's the only way
                // for this specific race test.

                kotlinx.coroutines.runBlocking { canCompleteSave.await() }
                SaveResult.Success
            }

            // 1. Start save operation
            val saveJob =
                launch {
                    viewModel.saveAndNotify()
                }
            runCurrent()
            saveStarted.await()

            // 2. Try to update settings while save is in progress
            // This update should be queued because updateSettings now uses saveMutex
            viewModel.updateSettings { it.copy(fontSize = 2) }
            runCurrent()

            // 3. Allow save to complete
            canCompleteSave.complete(Unit)
            saveJob.join()

            // 4. Verify that the FIRST save call captured the state BEFORE the update was applied
            // (Since the update was blocked by the lock)
            argumentCaptor<WidgetSettingsState>().apply {
                verify(saver).save(capture(), any())
                assertEquals(4, firstValue.fontSize)
            }

            // 5. Trigger second save to verify the update was NOT lost and is now in memory
            viewModel.saveAndNotify()
            runCurrent()

            argumentCaptor<WidgetSettingsState>().apply {
                verify(saver, org.mockito.kotlin.times(2)).save(capture(), any())
                assertEquals(2, lastValue.fontSize)
            }
        }
}
