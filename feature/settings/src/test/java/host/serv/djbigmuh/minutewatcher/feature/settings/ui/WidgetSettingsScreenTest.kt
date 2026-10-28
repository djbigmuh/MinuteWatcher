package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import host.serv.djbigmuh.minutewatcher.core.resources.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
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
        whenever(viewModel.aodSummaryAvailable)
            .doReturn(MutableStateFlow(true))
        whenever(viewModel.aodSummaryHasContent)
            .doReturn(MutableStateFlow(true))
    }

    private fun createEmptySnapshot() =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = 0L,
            simSnapshots = emptyList(),
            capabilities = emptyList(),
        )

    @Test
    fun `loading state is displayed`() {
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
    fun `error state is displayed`() {
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
    fun `settings content is displayed`() {
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
        val supportedLocales =
            listOf(
                "en",
                "ar",
                "bho",
                "bn",
                "de",
                "es",
                "fa",
                "fr",
                "gu",
                "ha",
                "hi",
                "id",
                "it",
                "ja",
                "jv",
                "ko",
                "mr",
                "pa",
                "pt-BR",
                "pt-PT",
                "ru",
                "ta",
                "te",
                "tr",
                "ur",
                "vi",
                "yo",
                "zh",
            )
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

    @Test
    fun aodSummaryTitleAndDescriptionDisplayed() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_description))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_unavailable))
            .assertDoesNotExist()
    }

    @Test
    fun aodSummaryUnavailableTextDisplayedWhenAodSummaryAvailableIsFalse() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))
        whenever(viewModel.aodSummaryAvailable).doReturn(MutableStateFlow(false))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_unavailable))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aodSummaryNoLimitsNoteDisplayedWhenEnabledAndAvailableAndNoContent() {
        val settings = testSettings.copy(aodSummaryEnabled = true)
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = settings)))
        whenever(viewModel.aodSummaryHasContent).doReturn(MutableStateFlow(false))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_no_limits_note))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aodSummaryNoLimitsNoteNotDisplayedWhenContentExists() {
        val settings = testSettings.copy(aodSummaryEnabled = true)
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = settings)))
        whenever(viewModel.aodSummaryHasContent).doReturn(MutableStateFlow(true))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_no_limits_note))
            .assertDoesNotExist()
    }

    @Test
    fun aodSummaryUnavailableHasPriorityOverNoLimitsNote() {
        val settings = testSettings.copy(aodSummaryEnabled = true)
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = settings)))
        whenever(viewModel.aodSummaryAvailable).doReturn(MutableStateFlow(false))
        whenever(viewModel.aodSummaryHasContent).doReturn(MutableStateFlow(false))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_unavailable))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.aod_summary_no_limits_note))
            .assertDoesNotExist()
    }

    @Test
    fun aodSummaryStringsAreNonEmptyInAllConfiguredLocales() {
        val supportedLocales =
            listOf(
                "en",
                "ar",
                "bho",
                "bn",
                "de",
                "es",
                "fa",
                "fr",
                "gu",
                "ha",
                "hi",
                "id",
                "it",
                "ja",
                "jv",
                "ko",
                "mr",
                "pa",
                "pt-BR",
                "pt-PT",
                "ru",
                "ta",
                "te",
                "tr",
                "ur",
                "vi",
                "yo",
                "zh",
            )
        val stringResIds =
            listOf(
                R.string.aod_summary_title,
                R.string.aod_summary_description,
                R.string.aod_summary_unavailable,
                R.string.notification_channel_aod_summary_name,
                R.string.notification_channel_aod_summary_desc,
            )

        supportedLocales.forEach { lang ->
            val locale = Locale.forLanguageTag(lang)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localizedContext = context.createConfigurationContext(config)

            stringResIds.forEach { resId ->
                val strName = context.resources.getResourceEntryName(resId)
                val valStr = localizedContext.getString(resId)
                assertTrue("String $strName for locale $lang must not be empty", valStr.isNotEmpty())
            }
        }
    }

    @Test
    fun trackingTitleAndDescriptionDisplayed() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.tracking_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.call_tracking_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.call_tracking_description))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.assistant_access_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.assistant_access_description))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun togglingCallTrackingCheckboxCallsUpdateSettings() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.call_tracking_title))
            .performScrollTo()
            .performClick()

        verify(viewModel).updateSettings(any())
    }

    @Test
    fun togglingAssistantAccessCheckboxCallsUpdateSettings() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.assistant_access_title))
            .performScrollTo()
            .performClick()

        verify(viewModel).updateSettings(any())
    }

    @Test
    fun trackingStringsAreNonEmptyInAllConfiguredLocales() {
        val supportedLocales =
            listOf(
                "en",
                "ar",
                "bho",
                "bn",
                "de",
                "es",
                "fa",
                "fr",
                "gu",
                "ha",
                "hi",
                "id",
                "it",
                "ja",
                "jv",
                "ko",
                "mr",
                "pa",
                "pt-BR",
                "pt-PT",
                "ru",
                "ta",
                "te",
                "tr",
                "ur",
                "vi",
                "yo",
                "zh",
            )
        val stringResIds =
            listOf(
                R.string.tracking_title,
                R.string.call_tracking_title,
                R.string.call_tracking_description,
                R.string.assistant_access_title,
                R.string.assistant_access_description,
            )

        supportedLocales.forEach { lang ->
            val locale = Locale.forLanguageTag(lang)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localizedContext = context.createConfigurationContext(config)

            stringResIds.forEach { resId ->
                val strName = context.resources.getResourceEntryName(resId)
                val valStr = localizedContext.getString(resId)
                assertTrue("String $strName for locale $lang must not be empty", valStr.isNotEmpty())
            }
        }
    }

    @Test
    fun clickingBackgroundColorRowShowsColorPickerDialog() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.custom_color)).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.save_button)).assertExists()
    }

    @Test
    fun confirmingColorPickerDialogCallsUpdateSettings() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.custom_color)).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.save_button)).performClick()
        composeTestRule.waitForIdle()

        verify(viewModel).updateSettings(any())
    }

    @Test
    fun confirmingColorPickerDialogClosesDialogAndCallsUpdateSettingsOnce() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.custom_color)).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.save_button)).performClick()
        composeTestRule.waitForIdle()

        verify(viewModel, org.mockito.kotlin.times(1)).updateSettings(any())
        composeTestRule.onNodeWithText(context.getString(R.string.save_button)).assertDoesNotExist()
    }

    @Test
    fun cancellingColorPickerDialogDoesNotCallUpdateSettingsAndClosesDialog() {
        whenever(viewModel.state).doReturn(MutableStateFlow(WidgetSettingsUiState(settings = testSettings)))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.custom_color)).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(R.string.cancel_button)).performClick()
        composeTestRule.waitForIdle()

        verify(viewModel, never()).updateSettings(any())
        composeTestRule.onNodeWithText(context.getString(R.string.save_button)).assertDoesNotExist()
    }

    @Test
    fun `sim cards section renders default state when slot settings are missing from simStates`() {
        val settings =
            WidgetSettingsState(
                simStates = emptyMap(),
                simListLoadFailed = false,
            )
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 12345L,
                simSnapshots =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "id0",
                            operatorName = "Operator",
                            entities = emptyMap(),
                            autoResetDay = 1,
                        ),
                    ),
                capabilities = emptyList(),
            )

        whenever(viewModel.state)
            .doReturn(MutableStateFlow(WidgetSettingsUiState(settings = settings)))
        whenever(viewModel.snapshot)
            .doReturn(MutableStateFlow(snapshot))

        composeTestRule.setContent {
            WidgetSettingsScreen(
                viewModel = viewModel,
                onBack = {},
            )
        }

        composeTestRule
            .onNodeWithText(
                context.getString(R.string.sim_card_generic, 1),
                substring = true,
            ).performScrollTo()
            .assertIsDisplayed()
    }
}
