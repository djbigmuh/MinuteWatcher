package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class DialogsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSimLimitDialog_InputValidation() {
        var capturedMinutes = -1
        
        composeTestRule.setContent {
            SimLimitComposeDialog(
                operatorName = "MTS",
                initialMinutes = 0,
                initialTrafficMb = 0,
                onDismiss = {},
                onConfirm = { minutes, _ -> capturedMinutes = minutes },
                onReset = {}
            )
        }

        // Minutes field
        composeTestRule.onNodeWithText("Set minutes limit", substring = true, ignoreCase = true)
            .performTextInput("abc123xyz")
        
        composeTestRule.onNodeWithText("Set", ignoreCase = true)
            .performClick()
            
        assert(capturedMinutes == 123)
    }

    @Test
    fun testSimLimitDialog_TrafficCommaReplacement() {
        var capturedTrafficMb = -1
        
        composeTestRule.setContent {
            SimLimitComposeDialog(
                operatorName = "MTS",
                initialMinutes = 0,
                initialTrafficMb = 0,
                onDismiss = {},
                onConfirm = { _, traffic -> capturedTrafficMb = traffic },
                onReset = {}
            )
        }

        // Traffic field
        composeTestRule.onNodeWithText("Set traffic limit", substring = true, ignoreCase = true)
            .performTextInput("1,5")
        
        composeTestRule.onNodeWithText("Set", ignoreCase = true)
            .performClick()
            
        // 1.5 GB * 1024 = 1536 MB
        assert(capturedTrafficMb == 1536)
    }
}
