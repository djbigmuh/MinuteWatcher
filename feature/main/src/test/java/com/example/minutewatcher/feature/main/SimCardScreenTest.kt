package com.example.minutewatcher.feature.main

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimCardScreenTest {
    @get:Rule
    val composeTestRule =
        androidx.compose.ui.test.junit4.v2
            .createComposeRule()

    @Test
    fun emptyState_isDisplayed() {
        composeTestRule.setContent {
            EmptyState()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedText = context.getString(R.string.no_sim_found)
        composeTestRule.onNodeWithText(expectedText).assertIsDisplayed()
    }

    @Test
    fun errorState_isDisplayedWithMessage() {
        val errorMessage = "Test Error Message"
        composeTestRule.setContent {
            ErrorState(message = errorMessage)
        }
        composeTestRule.onNodeWithText(errorMessage).assertIsDisplayed()
    }
}
