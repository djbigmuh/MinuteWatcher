package com.example.minutewatcher.feature.main.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // behavior-specific SDK
class SimLimitComposeDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun resetLimits_showsConfirmationDialog() {
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

        // Check if confirmation dialog is displayed
        composeTestRule.onNodeWithText(context.getString(R.string.reset_confirmation_title)).assertIsDisplayed()
        assertFalse("onReset should not be called yet", resetCalled)

        // Click "Cancel" in confirmation dialog (the last one added)
        composeTestRule.onAllNodesWithText(context.getString(R.string.cancel_button)).onLast().performClick()

        // Confirmation dialog should be gone
        composeTestRule.onNodeWithText(context.getString(R.string.reset_confirmation_title)).assertDoesNotExist()
        assertFalse("onReset should not be called after Cancel", resetCalled)

        // Click "Reset limits" button again
        composeTestRule.onNodeWithText(context.getString(R.string.reset_limits_button)).performClick()

        // Click "Reset" in confirmation dialog
        composeTestRule.onNodeWithText(context.getString(R.string.reset_label)).performClick()

        assertTrue("onReset should be called after confirmation", resetCalled)
    }
}
