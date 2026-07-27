package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minutewatcher.core.model.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testSimLimitDialog_InputValidation() {
        var capturedMinutes = -1
        val setLimitText = context.getString(R.string.set_minutes_limit)
        val setButtonText = context.getString(R.string.set_button)

        composeTestRule.setContent {
            SimLimitComposeDialog(
                operatorName = "MTS",
                initialMinutes = 0,
                initialTrafficMb = 0,
                onDismiss = {},
                onConfirm = { minutes, _ -> capturedMinutes = minutes },
                onReset = {},
            )
        }

        // Minutes field
        composeTestRule
            .onNodeWithText(setLimitText, substring = true, ignoreCase = true)
            .performTextInput("abc123xyz")

        composeTestRule
            .onNodeWithText(setButtonText, ignoreCase = true)
            .performClick()

        assert(capturedMinutes == 123)
    }

    @Test
    fun testSimLimitDialog_TrafficCommaReplacement() {
        var capturedTrafficMb = -1
        val setTrafficText = context.getString(R.string.set_traffic_limit)
        val setButtonText = context.getString(R.string.set_button)

        composeTestRule.setContent {
            SimLimitComposeDialog(
                operatorName = "MTS",
                initialMinutes = 0,
                initialTrafficMb = 0,
                onDismiss = {},
                onConfirm = { _, traffic -> capturedTrafficMb = traffic },
                onReset = {},
            )
        }

        // Traffic field
        composeTestRule
            .onNodeWithText(setTrafficText, substring = true, ignoreCase = true)
            .performTextInput("1,5")

        composeTestRule
            .onNodeWithText(setButtonText, ignoreCase = true)
            .performClick()

        // 1.5 GB * 1024 = 1536 MB
        assert(capturedTrafficMb == 1536)
    }
}
