package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import host.serv.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelSaveTest : BaseWidgetSettingsViewModelFixture() {
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
                        withTimeoutOrNull(30.seconds) { gate.await() }
                            ?: throw AssertionError("Timed out waiting for gate in blockingLoader")
                    }

                    override suspend fun saveSimSettings(state: WidgetSettingsState): List<Int> = emptyList()
                }
            val realSaver = WidgetSettingsSaver(blockingLoader, mock(), backgroundScope)
            whenever(loader.load()).thenReturn(WidgetSettingsState())
            val vm =
                WidgetSettingsViewModel(
                    loader = loader,
                    saver = realSaver,
                    appSettingsRepository = appSettingsRepository,
                    appScope = backgroundScope,
                    usageStateRepository = usageStateRepository,
                    aodSummaryAvailability = aodSummaryAvailability,
                )
            advanceUntilIdle()

            launch { vm.saveAndNotify() }
            runCurrent()
            assertTrue(vm.state.value.isSaving)

            val secondSave = launch { vm.saveAndNotify() }
            runCurrent()
            assertFalse(secondSave.isCompleted)

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(secondSave.isCompleted)
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
    fun `updateSettings updates callTrackingEnabled`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(callTrackingEnabled = false) }
            assertEquals(
                false,
                viewModel.state.value.settings
                    ?.callTrackingEnabled,
            )
        }

    @Test
    fun `updateSettings updates assistantAccessEnabled`() =
        runTest {
            initViewModel()
            viewModel.updateSettings { it.copy(assistantAccessEnabled = true) }
            assertEquals(
                true,
                viewModel.state.value.settings
                    ?.assistantAccessEnabled,
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
    fun `save failure GENERAL updates state with reason`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Failed(SaveFailureReason.GENERAL))

            viewModel.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.Failed(SaveFailureReason.GENERAL), viewModel.state.value.saveResult)
        }

    @Test
    fun `save failure NOT_LOADED returned when settings not loaded`() =
        runTest {
            whenever(loader.load()).thenThrow(IllegalStateException("Load failed"))
            initViewModel()

            viewModel.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.Failed(SaveFailureReason.NOT_LOADED), viewModel.state.value.saveResult)
        }

    @Test
    fun `save failure TIMEOUT returned when save times out`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenReturn(SaveResult.Failed(SaveFailureReason.TIMEOUT))

            viewModel.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.Failed(SaveFailureReason.TIMEOUT), viewModel.state.value.saveResult)
        }

    @Test
    fun `save with partial failure updates state correctly`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenReturn(SaveResult.PartialFailure(listOf(0)))

            viewModel.saveAndNotify()
            advanceUntilIdle()

            assertEquals(SaveResult.PartialFailure(listOf(0)), viewModel.state.value.saveResult)
        }

    @Test
    fun `save throws CancellationException rethrows`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenThrow(CancellationException("Canceled"))

            try {
                viewModel.saveAndNotify()
                advanceUntilIdle()
            } catch (e: CancellationException) {
                assertEquals("Canceled", e.message)
            }
        }

    @Test
    @host.serv.djbigmuh.minutewatcher.core.common.annotation.RunBlockingBridge("Cancellation exception assertion bridge")
    fun `performEmergencySave rethrows CancellationException`() =
        runTest {
            initViewModel()
            whenever(saver.save(any(), any())).thenThrow(CancellationException("Emergency save canceled"))

            org.junit.Assert.assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    viewModel.performEmergencySave(WidgetSettingsState())
                }
            }
        }
}
