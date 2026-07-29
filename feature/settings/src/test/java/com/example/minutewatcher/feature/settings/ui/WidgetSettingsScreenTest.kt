package com.example.minutewatcher.feature.settings.ui

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
class WidgetSettingsScreenTest {
    @get:Rule
    val composeTestRule = androidx.compose.ui.test.junit4.v2.createComposeRule()

    private val viewModel: WidgetSettingsViewModel = mock()

    @Test
    fun loadingState_isDisplayed() {
        whenever(viewModel.settingsState).thenReturn(MutableStateFlow(null))
        whenever(viewModel.uiState).thenReturn(MutableStateFlow(null))
        whenever(viewModel.loadError).thenReturn(MutableStateFlow(false))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onSave = {}
            )
        }
    }
}
