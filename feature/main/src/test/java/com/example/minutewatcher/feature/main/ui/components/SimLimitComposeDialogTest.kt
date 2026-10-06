package com.example.minutewatcher.feature.main.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class SimLimitComposeDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultLocale = Locale.US

    @Test
    fun `reset limits shows confirmation dialog`() {
        var resetCalled = false
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeTestRule.setContent {
            SimLimitComposeDialog(
                slot = 0,
                operatorName = "Test Operator",
                initialMinutes = 100,
                initialTrafficMb = 1000,
                onDismiss = {},
                onConfirm = { _, _, _ -> },
                onReset = { resetCalled = true },
            )
        }

        // Click "Reset limits" button
        composeTestRule.onNodeWithText(context.getString(R.string.reset_limits_button)).performClick()

        // Check if confirmation dialog with new limits reset text is displayed
        composeTestRule.onNodeWithText(context.getString(R.string.reset_limits_confirmation_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.reset_limits_confirmation_body)).assertIsDisplayed()
        assertFalse("onReset should not be called yet", resetCalled)

        // Click "Cancel" in confirmation dialog (the last one added)
        composeTestRule.onAllNodesWithText(context.getString(R.string.cancel_button)).onLast().performClick()

        // Confirmation dialog should be gone
        composeTestRule.onNodeWithText(context.getString(R.string.reset_limits_confirmation_title)).assertDoesNotExist()
        assertFalse("onReset should not be called after Cancel", resetCalled)

        // Click "Reset limits" button again
        composeTestRule.onNodeWithText(context.getString(R.string.reset_limits_button)).performClick()

        // Click "Reset" in confirmation dialog
        composeTestRule.onNodeWithText(context.getString(R.string.reset_label)).performClick()

        assertTrue("onReset should be called after confirmation", resetCalled)
    }

    @Test
    fun `ResetConfirmationDialog with default parameters displays counter reset text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeTestRule.setContent {
            ResetConfirmationDialog(
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.reset_confirmation_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.reset_confirmation_body)).assertIsDisplayed()
    }

    @Test
    fun `minutes - old 0, types 5 - results in 5`() {
        val state = SimLimitState("0", "0.0", false)
        // Simulate typing '5' at the end of "0"
        state.onMinutesChange(TextFieldValue("05", selection = TextRange(2)), defaultLocale)
        assertEquals("5", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, types 0 - results in 0`() {
        val state = SimLimitState("0", "0.0", false)
        // Simulate typing '0' at the end of "0"
        state.onMinutesChange(TextFieldValue("00", selection = TextRange(2)), defaultLocale)
        assertEquals("0", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, full selection, types 7 - results in 7`() {
        val state = SimLimitState("0", "0.0", false)
        state.selectAllMinutes()
        // Simulate replacing selection "0" with "7"
        state.onMinutesChange(TextFieldValue("7", selection = TextRange(1)), defaultLocale)
        assertEquals("7", state.minutesText.text)
    }

    @Test
    fun `minutes - old 12, full selection, types 5 - results in 5`() {
        val state = SimLimitState("12", "0.0", false)
        state.selectAllMinutes()
        // Simulate replacing selection "12" with "5"
        state.onMinutesChange(TextFieldValue("5", selection = TextRange(1)), defaultLocale)
        assertEquals("5", state.minutesText.text)
    }

    @Test
    fun `minutes - old 0, paste 100 - results in 100`() {
        val state = SimLimitState("0", "0.0", false)
        // Paste "100" at the end: "0100"
        state.onMinutesChange(TextFieldValue("0100", selection = TextRange(4)), defaultLocale)
        assertEquals("100", state.minutesText.text)
    }

    @Test
    fun `minutes - old 500, full selection, paste 100 - results in 100`() {
        val state = SimLimitState("500", "0.0", false)
        state.selectAllMinutes()
        // Replace "500" with "100"
        state.onMinutesChange(TextFieldValue("100", selection = TextRange(3)), defaultLocale)
        assertEquals("100", state.minutesText.text)
    }

    @Test
    fun `traffic - old 0_0, types 5 - results in 5`() {
        val state = SimLimitState("0", "0.0", false)
        // Simulate typing '5' at the end: "0.05"
        state.onTrafficChange(TextFieldValue("0.05", selection = TextRange(4)), defaultLocale)
        assertEquals("5", state.trafficText.text)
    }

    @Test
    fun `traffic - old 0_0, full selection, paste 15_5 - results in 15_5`() {
        val state = SimLimitState("0", "0.0", false)
        state.selectAllTraffic()
        // Replace "0.0" with "15.5"
        state.onTrafficChange(TextFieldValue("15.5", selection = TextRange(4)), defaultLocale)
        assertEquals("15.5", state.trafficText.text)
    }

    @Test
    fun `traffic - old 5, full selection, paste 500 - results in 500`() {
        val state = SimLimitState("0", "5", false)
        state.selectAllTraffic()
        // Replace "5" with "500"
        state.onTrafficChange(TextFieldValue("500", selection = TextRange(3)), defaultLocale)
        assertEquals("500", state.trafficText.text)
    }

    @Test
    fun `traffic - old 0, paste 10_5 - results in 10_5`() {
        val state = SimLimitState("0", "0", false)
        // Paste "10.5" at the end: "010.5"
        state.onTrafficChange(TextFieldValue("010.5", selection = TextRange(5)), defaultLocale)
        assertEquals("10.5", state.trafficText.text)
    }

    @Test
    fun `limit exceeded - ignores input`() {
        val state = SimLimitState("0", "0", false)
        // Max 6 chars for minutes. Try to type 7th.
        state.onMinutesChange(TextFieldValue("1234567", selection = TextRange(7)), defaultLocale)
        assertEquals("0", state.minutesText.text)
    }

    @Test
    fun `click traffic field then typing 7 replaces value instead of appending`() {
        composeTestRule.setContent {
            SimLimitComposeDialog(
                slot = 0,
                operatorName = "Test Operator",
                initialMinutes = 100,
                initialTrafficMb = 10240, // 10 GB ("10.0")
                onDismiss = {},
                onConfirm = { _, _, _ -> },
                onReset = {},
            )
        }

        // Click traffic field "10.0"
        composeTestRule.onNodeWithText("10.0").performClick()
        // Type "7"
        composeTestRule.onNodeWithText("10.0").performTextInput("7")

        // Traffic text should be "7", not "10.07"
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun `confirm with empty input shows validation error and does not call confirm`() {
        var confirmCalled = false
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeTestRule.setContent {
            SimLimitComposeDialog(
                slot = 0,
                operatorName = "Test Operator",
                initialMinutes = 0,
                initialTrafficMb = 0,
                onDismiss = {},
                onConfirm = { _, _, _ -> confirmCalled = true },
                onReset = {},
            )
        }

        // Clear the minutes text field
        composeTestRule.onNodeWithText("0").performTextClearance()

        // Click set button
        composeTestRule
            .onNodeWithText(context.getString(R.string.set_button), ignoreCase = true)
            .performClick()

        assertFalse("onConfirm should not be called with empty input", confirmCalled)
        composeTestRule
            .onNodeWithText(context.getString(R.string.validation_error_field_required))
            .assertIsDisplayed()
    }
}
