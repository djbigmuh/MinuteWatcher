package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.feature.main.controller.MainDialogController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelDialogTest : MainViewModelTestFixtures() {
    @Test
    fun `setLimitDialog updates showLimitDialog state`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            val data = LimitDialogData(slot = 1, name = "Test", minutes = 100, traffic = 1000)

            viewModel.setLimitDialog(data)

            assertEquals(data, viewModel.showLimitDialog.value)
        }

    @Test
    fun `setDayDialog updates showDayDialog state`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            val data = DayDialogData(slot = 1, day = 5)

            viewModel.setDayDialog(data)

            assertEquals(data, viewModel.showDayDialog.value)
        }

    @Test
    fun `setAdjustDialog updates showAdjustDialog state`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            val data = AdjustDialogData(slot = 1, currentValue = 50f, isMinutes = true)

            viewModel.setAdjustDialog(data)

            assertEquals(data, viewModel.showAdjustDialog.value)
        }

    @Test
    fun `setTrafficLimitationDialog updates showTrafficLimitationDialog state`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)

            viewModel.setTrafficLimitationDialog(true)
            assertEquals(true, viewModel.showTrafficLimitationDialog.value)

            viewModel.setTrafficLimitationDialog(false)
            assertEquals(false, viewModel.showTrafficLimitationDialog.value)
        }

    @Test
    fun `setUsageAccessDialog updates showUsageAccessDialog state`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)

            viewModel.setUsageAccessDialog(true)
            assertEquals(true, viewModel.showUsageAccessDialog.value)

            viewModel.setUsageAccessDialog(false)
            assertEquals(false, viewModel.showUsageAccessDialog.value)
        }

    @Test
    fun `completeOnboarding hides onboarding dialog`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)

            viewModel.completeOnboarding()
            assertEquals(false, viewModel.showOnboardingDialog.value)
        }

    @Test
    fun `onboarding dialog is not shown when settings are not loaded`() =
        testScope.runTest {
            val settingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = false, isLoaded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val viewModel = createViewModel(backgroundScope)

            assertEquals(false, viewModel.showOnboardingDialog.value)

            settingsFlow.value = AppSettings(onboardingCompleted = false, isLoaded = true)

            assertEquals(true, viewModel.showOnboardingDialog.value)
        }

    @Test
    fun `onboarding dialog is not shown while usage access dialog is open`() =
        testScope.runTest {
            val settingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = false, isLoaded = true))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val dialogController =
                MainDialogController(savedStateHandle)
            dialogController.setUsageAccessDialog(true)

            val viewModel = createViewModel(backgroundScope, dialogController)

            assertEquals(false, viewModel.showOnboardingDialog.value)
        }

    @Test
    fun `claimOnboardingPrompt returns true on first call and false on subsequent calls`() =
        testScope.runTest {
            val controller = MainDialogController(savedStateHandle)
            assertTrue(controller.claimOnboardingPrompt())
            assertFalse(controller.claimOnboardingPrompt())
            assertFalse(controller.claimOnboardingPrompt())
        }

    @Test
    fun `after setShowOnboardingDialog false claimOnboardingPrompt returns false`() =
        testScope.runTest {
            val controller = MainDialogController(savedStateHandle)
            controller.setShowOnboardingDialog(show = false)
            assertFalse(controller.claimOnboardingPrompt())
        }

    @Test
    fun `after completeOnboarding repeated settings emission does not show onboarding dialog`() =
        testScope.runTest {
            val settingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = false, isLoaded = true))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val viewModel = createViewModel(backgroundScope)
            assertEquals(true, viewModel.showOnboardingDialog.value)

            viewModel.completeOnboarding()
            assertEquals(false, viewModel.showOnboardingDialog.value)

            settingsFlow.value =
                AppSettings(
                    onboardingCompleted = false,
                    isLoaded = true,
                    inaccurateCallAttributionsCount = 1,
                )
            assertEquals(false, viewModel.showOnboardingDialog.value)
        }

    @Test
    fun `onboarding dialog is shown after usage access dialog closes`() =
        testScope.runTest {
            val settingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = false, isLoaded = true))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val dialogController = MainDialogController(savedStateHandle)
            dialogController.setUsageAccessDialog(true)

            val viewModel = createViewModel(backgroundScope, dialogController)
            assertEquals(false, viewModel.showOnboardingDialog.value)

            viewModel.setUsageAccessDialog(false)
            settingsFlow.value =
                AppSettings(
                    onboardingCompleted = false,
                    isLoaded = true,
                    inaccurateCallAttributionsCount = 2,
                )
            assertEquals(true, viewModel.showOnboardingDialog.value)
        }

    @Test
    fun `repeated emissions do not retrigger onboarding dialog when already shown`() =
        testScope.runTest {
            val settingsFlow = MutableStateFlow(AppSettings(onboardingCompleted = false, isLoaded = true))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(settingsFlow)

            val viewModel = createViewModel(backgroundScope)
            assertEquals(true, viewModel.showOnboardingDialog.value)

            settingsFlow.value =
                AppSettings(
                    onboardingCompleted = false,
                    isLoaded = true,
                    inaccurateCallAttributionsCount = 3,
                )
            assertEquals(true, viewModel.showOnboardingDialog.value)
        }
}
