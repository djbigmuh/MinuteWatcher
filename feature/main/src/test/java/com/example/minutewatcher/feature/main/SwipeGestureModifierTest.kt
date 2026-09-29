package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.android.ui.HorizontalSwipeDirection
import com.example.minutewatcher.core.android.ui.onHorizontalSwipe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class SwipeGestureModifierTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `right to left swipe exceeding threshold triggers callback once`() {
        var callbackCount = 0
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onHorizontalSwipe(
                            direction = HorizontalSwipeDirection.RIGHT_TO_LEFT,
                            threshold = 40.dp,
                        ) { callbackCount++ },
            )
        }

        // Swipe right-to-left: from x = 300 to x = 100 (dx = -200px, which exceeds 40dp)
        composeTestRule.onRoot().performTouchInput {
            swipe(
                start = Offset(300f, 200f),
                end = Offset(100f, 200f),
                durationMillis = 200,
            )
        }

        assertEquals(1, callbackCount)
    }

    @Test
    fun `right to left swipe below threshold does not trigger callback`() {
        var callbackCount = 0
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onHorizontalSwipe(
                            direction = HorizontalSwipeDirection.RIGHT_TO_LEFT,
                            threshold = 40.dp,
                        ) { callbackCount++ },
            )
        }

        // Swipe right-to-left: from x = 100 to x = 90 (dx = -10px, below 40dp)
        composeTestRule.onRoot().performTouchInput {
            swipe(
                start = Offset(100f, 200f),
                end = Offset(90f, 200f),
                durationMillis = 200,
            )
        }

        assertEquals(0, callbackCount)
    }

    @Test
    fun `right to left swipe in opposite direction does not trigger callback`() {
        var callbackCount = 0
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onHorizontalSwipe(
                            direction = HorizontalSwipeDirection.RIGHT_TO_LEFT,
                            threshold = 40.dp,
                        ) { callbackCount++ },
            )
        }

        // Swipe left-to-right (opposite direction)
        composeTestRule.onRoot().performTouchInput {
            swipe(
                start = Offset(100f, 200f),
                end = Offset(300f, 200f),
                durationMillis = 200,
            )
        }

        assertEquals(0, callbackCount)
    }

    @Test
    fun `left to right swipe exceeding threshold triggers callback once`() {
        var callbackCount = 0
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onHorizontalSwipe(
                            direction = HorizontalSwipeDirection.LEFT_TO_RIGHT,
                            threshold = 40.dp,
                        ) { callbackCount++ },
            )
        }

        // Swipe left-to-right: from x = 100 to x = 300 (dx = +200px, exceeds 40dp)
        composeTestRule.onRoot().performTouchInput {
            swipe(
                start = Offset(100f, 200f),
                end = Offset(300f, 200f),
                durationMillis = 200,
            )
        }

        assertEquals(1, callbackCount)
    }

    @Test
    fun `left to right swipe below threshold does not trigger callback`() {
        var callbackCount = 0
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onHorizontalSwipe(
                            direction = HorizontalSwipeDirection.LEFT_TO_RIGHT,
                            threshold = 40.dp,
                        ) { callbackCount++ },
            )
        }

        // Swipe left-to-right: from x = 100 to x = 110 (dx = +10px, below 40dp)
        composeTestRule.onRoot().performTouchInput {
            swipe(
                start = Offset(100f, 200f),
                end = Offset(110f, 200f),
                durationMillis = 200,
            )
        }

        assertEquals(0, callbackCount)
    }

    @Test
    fun `left to right swipe in opposite direction does not trigger callback`() {
        var callbackCount = 0
        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onHorizontalSwipe(
                            direction = HorizontalSwipeDirection.LEFT_TO_RIGHT,
                            threshold = 40.dp,
                        ) { callbackCount++ },
            )
        }

        // Swipe right-to-left (opposite direction)
        composeTestRule.onRoot().performTouchInput {
            swipe(
                start = Offset(300f, 200f),
                end = Offset(100f, 200f),
                durationMillis = 200,
            )
        }

        assertEquals(0, callbackCount)
    }
}
