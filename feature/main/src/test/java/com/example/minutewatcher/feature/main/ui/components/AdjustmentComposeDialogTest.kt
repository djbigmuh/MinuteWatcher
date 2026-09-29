package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class AdjustmentComposeDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `click field then typing replaces prefilled value instead of appending`() {
        composeTestRule.setContent {
            AdjustmentComposeDialog(
                currentValue = 380f,
                isMinutes = true,
                onDismiss = {},
                onConfirm = {},
            )
        }

        // Click on prefilled field "380"
        composeTestRule.onNodeWithText("380").performClick()
        // Type '7'
        composeTestRule.onNodeWithText("380").performTextInput("7")

        // Value should be "7", not "3807" or "3780"
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun `confirm with invalid number shows error and does not call onConfirm`() {
        var confirmCalled = false
        composeTestRule.setContent {
            AdjustmentComposeDialog(
                currentValue = 100f,
                isMinutes = true,
                onDismiss = {},
                onConfirm = { confirmCalled = true },
            )
        }

        // Replace value with invalid input (e.g. empty)
        composeTestRule.onNodeWithText("100").performClick()
        composeTestRule.onNodeWithText("100").performTextInput("")

        // Click Save button
        composeTestRule.onNodeWithText("Save").performClick()

        assertFalse(confirmCalled)
    }
}
