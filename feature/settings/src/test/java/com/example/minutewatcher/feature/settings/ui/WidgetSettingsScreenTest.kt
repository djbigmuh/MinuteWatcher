package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun widgetSettingsScreen_basicRendering() {
        val viewModel = mock<WidgetSettingsViewModel>()

        val defaultState =
            WidgetSettingsState(
                layoutMode = 0,
                simArrangement = 0,
                transparency = 50,
                fontSize = 2,
                backgroundColor = 0xFF000000.toInt(),
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                callTrackingEnabled = true,
                simStates =
                    listOf(
                        SimSettingsState(true, true, true, true, false, true, false),
                        SimSettingsState(true, true, true, true, false, true, false),
                    ),
            )

        whenever(viewModel.settingsState).thenReturn(MutableStateFlow(defaultState))
        whenever(viewModel.uiState).thenReturn(MutableStateFlow(null))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onSave = {},
                onBack = {},
            )
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedTitle = context.getString(R.string.settings_widget_title)
        composeTestRule.onNodeWithText(expectedTitle).assertIsDisplayed()
    }
}
