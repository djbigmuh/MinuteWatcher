package com.example.minutewatcher.feature.settings.ui

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.feature.widget.ui.WidgetPreviewBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetSettingsScreenTest {

    @get:Rule
    val composeTestRule = androidx.compose.ui.test.junit4.v2.createComposeRule()

    @Test
    fun widgetSettingsScreen_basicRendering() {
        val viewModel = mock<WidgetSettingsViewModel>()
        val previewBuilder = mock<WidgetPreviewBuilder>()
        
        val defaultState = WidgetSettingsState(
            layoutMode = 0,
            simArrangement = 0,
            transparency = 50,
            fontSize = 2,
            backgroundColor = 0xFF000000.toInt(),
            notifyLowMinutes = true,
            notifyLowTraffic = true,
            callTrackingEnabled = true,
            simStates = listOf(
                SimSettingsState(true, true, true, true, false, true, false),
                SimSettingsState(true, true, true, true, false, true, false)
            )
        )

        whenever(viewModel.settingsState).thenReturn(MutableStateFlow(defaultState))
        whenever(viewModel.uiState).thenReturn(MutableStateFlow(null))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onSave = {},
                onBack = {},
                previewBuilder = previewBuilder
            )
        }
    }
}
