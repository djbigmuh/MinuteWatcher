package com.example.minutewatcher.feature.settings.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutRow
import com.example.minutewatcher.core.model.SimLayoutSpec
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class WidgetPreviewLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createSimUiState(
        slot: Int,
        operatorName: String,
    ): SimUiState =
        SimUiState(
            slot = slot,
            operatorName = operatorName,
            operatorIconRes = 0,
            showSim = true,
            showIcon = false,
            showName = true,
            showMinutes = true,
            minutesText = "100",
            minutesColor = 0xFFFFFFFF.toInt(),
            showMinutesLimit = false,
            minutesLimitText = "",
            showTraffic = true,
            trafficText = "5.0 GB",
            trafficColor = 0xFFFFFFFF.toInt(),
            showTrafficLimit = false,
            trafficLimitText = "",
            fontMultiplier = 1.0f,
            textColor = 0xFFFFFFFF.toInt(),
            secondaryTextColor = 0xFF888888.toInt(),
            layoutSpec =
                SimLayoutSpec(
                    rows =
                        listOf(
                            SimLayoutRow(
                                components =
                                    listOf(
                                        SimLayoutComponent.Text(
                                            text = operatorName,
                                            color = 0xFFFFFFFF.toInt(),
                                            fontSizeSp = 14f,
                                        ),
                                    ),
                            ),
                        ),
                ),
        )

    private fun createWidgetUiState(
        sims: List<SimUiState>,
        isHorizontal: Boolean = true,
    ): WidgetUiState =
        WidgetUiState(
            layoutMode = 1,
            simArrangement = 0,
            simUiStates = sims,
            isHorizontal = isHorizontal,
            showSeparator = true,
            transparency = 50,
            backgroundColor = 0xFF000000.toInt(),
        )

    @Test
    fun `horizontal layout renders both SIMs with equal widths`() {
        val sim0 = createSimUiState(0, "MTS")
        val sim1 = createSimUiState(1, "Beeline Long Operator Name")
        val uiState = createWidgetUiState(listOf(sim0, sim1), isHorizontal = true)

        composeTestRule.setContent {
            WidgetPreview(uiState = uiState)
        }

        val desc0 = "MTS: 100, 5.0 GB"
        val desc1 = "Beeline Long Operator Name: 100, 5.0 GB"

        composeTestRule.onNodeWithContentDescription(desc0).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(desc1).assertIsDisplayed()

        val bounds0 = composeTestRule.onNodeWithContentDescription(desc0).getUnclippedBoundsInRoot()
        val bounds1 = composeTestRule.onNodeWithContentDescription(desc1).getUnclippedBoundsInRoot()

        val width0 = (bounds0.right - bounds0.left).value
        val width1 = (bounds1.right - bounds1.left).value
        val diff = abs(width0 - width1)
        assertTrue(
            "Widths of SIM boxes in horizontal layout should be equal within 1dp tolerance: diff=$diff",
            diff <= 1f,
        )
    }

    @Test
    fun `vertical layout renders both SIMs without exception`() {
        val sim0 = createSimUiState(0, "MTS")
        val sim1 = createSimUiState(1, "Beeline")
        val uiState = createWidgetUiState(listOf(sim0, sim1), isHorizontal = false)

        composeTestRule.setContent {
            WidgetPreview(uiState = uiState)
        }

        val desc0 = "MTS: 100, 5.0 GB"
        val desc1 = "Beeline: 100, 5.0 GB"

        composeTestRule.onNodeWithContentDescription(desc0).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(desc1).assertIsDisplayed()
    }

    @Test
    fun `single SIM layout renders without exception`() {
        val sim0 = createSimUiState(0, "MegaFon")
        val uiState = createWidgetUiState(listOf(sim0), isHorizontal = true)

        composeTestRule.setContent {
            WidgetPreview(uiState = uiState)
        }

        val desc0 = "MegaFon: 100, 5.0 GB"
        composeTestRule.onNodeWithContentDescription(desc0).assertIsDisplayed()
    }

    @Test
    fun `preview shows noSimFoundText when snapshot is non-empty but all sims hidden`() {
        val sim0 = createSimUiState(0, "MTS").copy(showSim = false)
        val uiState =
            createWidgetUiState(listOf(sim0), isHorizontal = true).copy(
                strings =
                    com.example.minutewatcher.core.model.WidgetStrings(
                        usedText = "",
                        minutesAbbr = "",
                        limitText = "",
                        gbAbbr = "",
                        noSimFoundText = "Custom SIM not found",
                    ),
                isSnapshotEmpty = false,
            )

        composeTestRule.setContent {
            WidgetPreview(uiState = uiState)
        }

        composeTestRule.onNodeWithText("Custom SIM not found").assertIsDisplayed()
    }

    @Test
    fun `preview shows settingsLoadErrorText when simListLoadFailed`() {
        val uiState =
            createWidgetUiState(emptyList(), isHorizontal = true).copy(
                strings =
                    com.example.minutewatcher.core.model.WidgetStrings(
                        usedText = "",
                        minutesAbbr = "",
                        limitText = "",
                        gbAbbr = "",
                        settingsLoadErrorText = "Custom Settings Error",
                    ),
                simListLoadFailed = true,
            )

        composeTestRule.setContent {
            WidgetPreview(uiState = uiState)
        }

        composeTestRule.onNodeWithText("Custom Settings Error").assertIsDisplayed()
    }

    @Test
    fun `preview shows noDataText when snapshot is empty`() {
        val uiState =
            createWidgetUiState(emptyList(), isHorizontal = true).copy(
                strings =
                    com.example.minutewatcher.core.model.WidgetStrings(
                        usedText = "",
                        minutesAbbr = "",
                        limitText = "",
                        gbAbbr = "",
                        noDataText = "Custom No Data",
                    ),
                isSnapshotEmpty = true,
            )

        composeTestRule.setContent {
            WidgetPreview(uiState = uiState)
        }

        composeTestRule.onNodeWithText("Custom No Data").assertIsDisplayed()
    }

    @Test
    fun `stale data indicator padding layout parity`() {
        val sim0 = createSimUiState(0, "MTS")
        val sim1 = createSimUiState(1, "Beeline")
        val warningText = "Unique Stale Data Warning Text"
        val uiState =
            createWidgetUiState(listOf(sim0, sim1), isHorizontal = true).copy(
                refreshFailed = true,
                strings =
                    com.example.minutewatcher.core.model.WidgetStrings(
                        usedText = "",
                        minutesAbbr = "",
                        limitText = "",
                        gbAbbr = "",
                        staleDataWarningText = warningText,
                    ),
            )

        composeTestRule.setContent {
            WidgetPreview(uiState = uiState)
        }

        composeTestRule.onNodeWithText(warningText).assertIsDisplayed()

        val warningBounds = composeTestRule.onNodeWithText(warningText).getUnclippedBoundsInRoot()
        val desc0 = "MTS: 100, 5.0 GB"
        val simBounds = composeTestRule.onNodeWithContentDescription(desc0).getUnclippedBoundsInRoot()
        println("DEBUG warningBounds: $warningBounds, simBounds: $simBounds")

        val gap = (simBounds.top - warningBounds.bottom).value
        assertTrue(
            "Gap between warning text bottom and SIM cards top should be <= 5dp: gap=$gap",
            gap <= 5f,
        )

        val topPadding = warningBounds.top.value
        assertTrue(
            "Top padding from preview top to warning text should be >= 3dp: top=$topPadding",
            topPadding >= 3f,
        )
    }
}
