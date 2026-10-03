package com.example.minutewatcher.feature.settings.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class WidgetSettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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
        whenever(viewModel.state)
            .doReturn(MutableStateFlow(WidgetSettingsUiState()))
        whenever(viewModel.snapshot)
            .doReturn(MutableStateFlow(createEmptySnapshot()))
    }

    private fun createEmptySnapshot() =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 0L,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )

    @Test
    fun loadingState_isDisplayed() {
        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            ).assertIsDisplayed()
    }

    @Test
    fun errorState_isDisplayed() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(isLoadError = true)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.error)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.action_retry)).assertIsDisplayed()
    }

    @Test
    fun settingsContent_isDisplayed() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.layout_mode_title)).assertIsDisplayed()
    }

    @Test
    fun backButtonHasContentDescriptionInErrorState() {
        val navUpDesc = context.getString(R.string.navigate_up_content_desc)
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(isLoadError = true)))
        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }
        composeTestRule.onNodeWithContentDescription(navUpDesc).assertIsDisplayed()
    }

    @Test
    fun backButtonHasContentDescriptionInContentState() {
        val navUpDesc = context.getString(R.string.navigate_up_content_desc)
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))
        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }
        composeTestRule.onNodeWithContentDescription(navUpDesc).assertIsDisplayed()
    }

    @Test
    fun navigateUpContentDescResolvesAndIsNotEmptyInAllConfiguredLocales() {
        val supportedLocales = listOf("en", "ar", "de", "es", "fr", "hi", "ja", "ru", "zh")
        supportedLocales.forEach { lang ->
            val locale = Locale.forLanguageTag(lang)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localizedContext = context.createConfigurationContext(config)

            val stringVal = localizedContext.getString(R.string.navigate_up_content_desc)
            assertTrue("String navigate_up_content_desc for locale $lang must not be empty", stringVal.isNotEmpty())
        }
    }

    @Test
    fun snackbarForPartialFailureWithSlotListDisplaysLocalizedTextWithUserSlotNumber() {
        val expectedText = context.getString(R.string.settings_save_partial_failure_slots, "1")
        whenever(viewModel.state).doReturn(
            MutableStateFlow(
                WidgetSettingsUiState(
                    settings = testSettings,
                    saveResult = SaveResult.PartialFailure(listOf(0)),
                ),
            ),
        )

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(expectedText).assertIsDisplayed()
    }

    @Test
    fun snackbarForPartialFailureWithEmptyListDisplaysGeneralLocalizedFailureText() {
        val expectedText = context.getString(R.string.settings_save_partial_failure)
        whenever(viewModel.state).doReturn(
            MutableStateFlow(
                WidgetSettingsUiState(
                    settings = testSettings,
                    saveResult = SaveResult.PartialFailure(emptyList()),
                ),
            ),
        )

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(expectedText).assertIsDisplayed()
    }

    @Test
    fun selectableLayoutModesMatchResourceArraySizeAndAreNonEmpty() {
        val layoutModes = context.resources.getStringArray(R.array.layout_modes)
        val selectableCount = Constants.Widget.MAX_SELECTABLE_LAYOUT_MODE - Constants.Widget.MIN_LAYOUT_MODE + 1
        assertEquals(
            "Number of selectable layout modes must equal layout_modes array length",
            selectableCount,
            layoutModes.size,
        )

        for (i in Constants.Widget.MIN_LAYOUT_MODE..Constants.Widget.MAX_SELECTABLE_LAYOUT_MODE) {
            val state = WidgetSettingsState(layoutMode = i)
            val validated = WidgetSettingsState.validate(state)
            val label = layoutModes.getOrNull(validated.layoutMode).orEmpty()
            assertTrue("Layout mode $i must produce a non-empty label", label.isNotEmpty())
        }
    }
}
