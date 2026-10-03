package com.example.minutewatcher.feature.main

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class SimCardScreenTest {
    @get:Rule
    val composeTestRule =
        createComposeRule()

    @Test
    fun `empty state is displayed`() {
        composeTestRule.setContent {
            EmptyState(onGrantPermission = {})
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedText = context.getString(R.string.no_sim_found)
        composeTestRule.onNodeWithText(expectedText).assertIsDisplayed()
    }

    @Test
    fun `error state is displayed with message`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedText = context.getString(R.string.refresh_error)
        composeTestRule.setContent {
            ErrorState(messageRes = R.string.refresh_error)
        }
        composeTestRule.onNodeWithText(expectedText).assertIsDisplayed()
    }
}
