package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.minutewatcher.core.model.SimCardUiModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.minutewatcher.core.resources.R as coreR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class SimCardItemTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `traffic limit text uses effective limit with adjustment`() {
        val model =
            createMockSimCard(
                trafficLimitMb = 1024, // 1 GB
                trafficAdjustmentGb = 0.5f,
                effectiveTrafficLimitGb = 1.5f,
                isTrafficLimitSet = true,
            )

        composeTestRule.setContent {
            SimCardItem(model = model)
        }

        // Search for text containing "1.5" (effective limit)
        composeTestRule.onNodeWithText("1.5", substring = true).assertIsDisplayed()
    }

    @Test
    fun `traffic adjustment mark is visible when adjustment is non zero`() {
        val model =
            createMockSimCard(
                trafficAdjustmentGb = 0.5f,
                isTrafficLimitSet = true,
            )

        composeTestRule.setContent {
            SimCardItem(model = model)
        }

        // Adjustment mark shows the adjustment value itself
        composeTestRule.onNodeWithText("+0.5", substring = true).assertIsDisplayed()
    }

    private fun createMockSimCard(
        trafficLimitMb: Int = 0,
        trafficAdjustmentGb: Float = 0f,
        effectiveTrafficLimitGb: Float = 0f,
        isTrafficLimitSet: Boolean = false,
    ) = SimCardUiModel(
        slot = 0,
        identity = "sim1",
        operatorName = "MTS",
        iconRes = coreR.drawable.ic_unknown,
        minutesUsed = 0,
        minutesLimit = 0,
        manualAdjustment = 0,
        minutesRemaining = 0,
        effectiveMinutesLimit = 0,
        isMinutesLimitSet = false,
        trafficUsedMb = 0f,
        trafficLimitMb = trafficLimitMb,
        trafficRemainingGb = 0f,
        effectiveTrafficLimitGb = effectiveTrafficLimitGb,
        trafficAdjustmentGb = trafficAdjustmentGb,
        isTrafficLimitSet = isTrafficLimitSet,
        autoResetEnabled = false,
        autoResetDay = 1,
        rolloverEnabled = false,
    )
}
