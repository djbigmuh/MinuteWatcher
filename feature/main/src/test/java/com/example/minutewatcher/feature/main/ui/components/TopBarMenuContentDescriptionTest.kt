package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.example.minutewatcher.core.model.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(qualifiers = "ru", sdk = [35])
@RunWith(RobolectricTestRunner::class)
class TopBarMenuContentDescriptionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `language menu displays localized content description in Russian`() {
        composeTestRule.setContent {
            LanguageMenu(currentLanguage = null) {}
        }

        composeTestRule.onNodeWithContentDescription("Смена языка").assertIsDisplayed()
    }

    @Test
    fun `theme menu displays localized content description in Russian`() {
        composeTestRule.setContent {
            ThemeMenu(currentTheme = AppTheme.FOLLOW_SYSTEM) {}
        }

        composeTestRule.onNodeWithContentDescription("Смена темы").assertIsDisplayed()
    }
}
