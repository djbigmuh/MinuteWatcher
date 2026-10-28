package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSettingsViewModelLoadTest : BaseWidgetSettingsViewModelFixture() {
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
            whenever(loader.load()).thenThrow(IllegalStateException("Fail")).thenReturn(WidgetSettingsState())

            val initialViewModel =
                WidgetSettingsViewModel(
                    loader = loader,
                    saver = saver,
                    appSettingsRepository = appSettingsRepository,
                    appScope = backgroundScope,
                    usageStateRepository = usageStateRepository,
                    aodSummaryAvailability = aodSummaryAvailability,
                )
            advanceUntilIdle()
            assertTrue(initialViewModel.state.value.isLoadError)

            initialViewModel.retryLoad()
            advanceUntilIdle()
            assertFalse(initialViewModel.state.value.isLoadError)
            assertNotNull(initialViewModel.state.value.settings)
        }
}
