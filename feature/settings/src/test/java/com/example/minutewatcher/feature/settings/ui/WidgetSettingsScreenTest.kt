package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSettingsScreenTest {
    @get:Rule
    val composeTestRule =
        androidx.compose.ui.test.junit4.v2
            .createComposeRule()

    private lateinit var viewModel: WidgetSettingsViewModel
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val testSettings =
        WidgetSettingsState(
            layoutMode = 0,
            simArrangement = 0,
            transparency = 50,
            fontSize = 2,
            backgroundColor = 0xFF000000.toInt(),
            notifyLowMinutes = true,
            notifyLowTraffic = true,
            simStates =
                mapOf(
                    0 to SimSettingsState(true, true, true, true, true, true, true),
                ),
        )

    @Before
    fun setup() {
        viewModel = mock()
        // Provide default stubs to avoid NPE on collectAsStateWithLifecycle
        whenever(viewModel.settingsState)
            .doReturn(MutableStateFlow(null))
        whenever(viewModel.snapshot)
            .doReturn(MutableStateFlow(createEmptySnapshot()))
        whenever(viewModel.loadError)
            .doReturn(MutableStateFlow(false))
        whenever(viewModel.isSaving)
            .doReturn(MutableStateFlow(false))
    }

    private fun createEmptySnapshot() =
        com.example.minutewatcher.core.model.GlobalSnapshot(
            version =
                com.example.minutewatcher.core.model
                    .SchemaVersion(1, 0, 0),
            timestamp = 0L,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )

    @Test
    fun loadingState_isDisplayed() {
        // settingsState is already null by default from @Before

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onSave = {},
            )
        }

        // CircularProgressIndicator has ProgressBarRangeInfo property
        composeTestRule
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            ).assertIsDisplayed()
    }

    @Test
    fun errorState_isDisplayed() {
        whenever(viewModel.loadError).doReturn(MutableStateFlow(true))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onSave = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.error)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed()
    }

    @Test
    fun settingsContent_isDisplayed() {
        whenever(viewModel.settingsState).doReturn(MutableStateFlow(testSettings))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onSave = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.layout_mode_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.save_button)).assertIsDisplayed()
    }

    @Test
    fun saveButton_triggersCallback() {
        whenever(viewModel.settingsState).doReturn(MutableStateFlow(testSettings))

        var saved = false
        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onSave = { saved = true },
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.save_button)).performClick()
        org.junit.Assert.assertTrue(saved)
    }
}
