package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import host.serv.djbigmuh.minutewatcher.core.domain.AodSummaryAvailability
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelRaceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val loader: WidgetSettingsLoader = mock()
    private val saver: WidgetSettingsSaver = mock()
    private val usageStateRepository: UsageStateRepository = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val aodSummaryAvailability: AodSummaryAvailability = mock()

    private lateinit var viewModel: WidgetSettingsViewModel

    @Before
    fun setup() =
        runTest {
            val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
            val snapshotFlow = MutableStateFlow(snapshot)
            whenever(usageStateRepository.observeSnapshot()).thenReturn(snapshotFlow)
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(MutableStateFlow(AppSettings()))
            whenever(loader.load()).thenReturn(WidgetSettingsState(fontSize = 4))
            whenever(aodSummaryAvailability.isAodSummaryAvailable()).thenReturn(true)

            viewModel =
                WidgetSettingsViewModel(
                    loader = loader,
                    saver = saver,
                    appSettingsRepository = appSettingsRepository,
                    appScope = backgroundScope,
                    usageStateRepository = usageStateRepository,
                    aodSummaryAvailability = aodSummaryAvailability,
                )
            advanceUntilIdle()
        }

    @Test
    fun `saveAndNotify persists updates made during the lock acquisition`() =
        runTest {
            val saveStarted = CompletableDeferred<Unit>()
            val canCompleteSave = CompletableDeferred<Unit>()
            val capturedStates = mutableListOf<WidgetSettingsState>()

            val controlledSaver = mock<WidgetSettingsSaver>()
            whenever(controlledSaver.save(any(), any())).doSuspendableAnswer { invocation ->
                val state = invocation.getArgument<WidgetSettingsState>(0)
                capturedStates.add(state)
                saveStarted.complete(Unit)
                withTimeoutOrNull(30.seconds) { canCompleteSave.await() }
                    ?: throw AssertionError("Timed out waiting for canCompleteSave in controlledSaver")
                SaveResult.Success
            }

            val vm =
                WidgetSettingsViewModel(
                    loader = loader,
                    saver = controlledSaver,
                    appSettingsRepository = appSettingsRepository,
                    appScope = backgroundScope,
                    usageStateRepository = usageStateRepository,
                    aodSummaryAvailability = aodSummaryAvailability,
                )
            advanceUntilIdle()

            // 1. Start save operation
            val saveJob =
                launch {
                    vm.saveAndNotify()
                }
            runCurrent()
            withTimeoutOrNull(30.seconds) { saveStarted.await() }
                ?: throw AssertionError("Timed out waiting for saveStarted")

            // 2. Try to update settings while save is in progress
            // This update is NOT blocked by saveMutex in memory,
            // but the RESULTING auto-save will be blocked.
            vm.updateSettings { it.copy(fontSize = 2) }
            runCurrent()

            // 3. Allow first save to complete
            canCompleteSave.complete(Unit)
            withTimeoutOrNull(30.seconds) { saveJob.join() }
                ?: throw AssertionError("Timed out waiting for saveJob")

            // 4. Verify that the FIRST save call captured the state BEFORE the update was applied
            assertEquals("First save should see old state", 4, capturedStates.first().fontSize)

            // 5. Trigger second save to verify the update was NOT lost and is now in memory
            vm.saveAndNotify()
            runCurrent()

            assertEquals("Second save should see updated state", 2, capturedStates.last().fontSize)
            assertEquals("Total saves should be 2", 2, capturedStates.size)
        }
}
