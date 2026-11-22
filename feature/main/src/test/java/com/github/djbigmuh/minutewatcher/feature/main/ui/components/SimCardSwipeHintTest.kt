package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.android.ui.HorizontalSwipeDirection
import com.github.djbigmuh.minutewatcher.core.android.ui.onHorizontalSwipe
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class SimCardSwipeHintTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `hint is displayed when gesture is available and hint not dismissed in LTR`() {
        composeTestRule.setContent {
            SimCardSwipeHint(
                isSwipeAvailable = true,
                isSwipeHintDismissed = false,
                swipeDirection = HorizontalSwipeDirection.RIGHT_TO_LEFT,
            )
        }

        val hintNode = composeTestRule.onNodeWithTag("swipe_hint_indicator")
        hintNode.assertIsDisplayed()

        val bounds = hintNode.getUnclippedBoundsInRoot()
        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()

        val rootWidth = rootBounds.right - rootBounds.left
        assertTrue(bounds.left > rootBounds.left + rootWidth / 2)
    }

    @Test
    fun `hint is placed at opposite edge when rendered in RTL`() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SimCardSwipeHint(
                    isSwipeAvailable = true,
                    isSwipeHintDismissed = false,
                    swipeDirection = HorizontalSwipeDirection.LEFT_TO_RIGHT,
                )
            }
        }

        val hintNode = composeTestRule.onNodeWithTag("swipe_hint_indicator")
        hintNode.assertIsDisplayed()

        val bounds = hintNode.getUnclippedBoundsInRoot()
        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()

        val rootWidth = rootBounds.right - rootBounds.left
        assertTrue(bounds.right < rootBounds.left + rootWidth / 2)
    }

    @Test
    fun `hint does not exist when swipe is unavailable`() {
        composeTestRule.setContent {
            SimCardSwipeHint(
                isSwipeAvailable = false,
                isSwipeHintDismissed = false,
                swipeDirection = HorizontalSwipeDirection.RIGHT_TO_LEFT,
            )
        }

        composeTestRule.onNodeWithTag("swipe_hint_indicator").assertDoesNotExist()
    }

    @Test
    fun `hint does not exist when hint is dismissed`() {
        composeTestRule.setContent {
            SimCardSwipeHint(
                isSwipeAvailable = true,
                isSwipeHintDismissed = true,
                swipeDirection = HorizontalSwipeDirection.RIGHT_TO_LEFT,
            )
        }

        composeTestRule.onNodeWithTag("swipe_hint_indicator").assertDoesNotExist()
    }

    @Test
    fun `performing swipe hides hint and card taps in hint zone continue working`() {
        var hintDismissed by mutableStateOf(false)
        var buttonClicked by mutableStateOf(false)

        composeTestRule.setContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onHorizontalSwipe(
                            HorizontalSwipeDirection.RIGHT_TO_LEFT,
                            50.dp,
                        ) {
                            hintDismissed = true
                        },
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag("card_container")
                            .clickable { buttonClicked = true },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(text = "Card Content")
                }

                SimCardSwipeHint(
                    isSwipeAvailable = true,
                    isSwipeHintDismissed = hintDismissed,
                    swipeDirection = HorizontalSwipeDirection.RIGHT_TO_LEFT,
                )
            }
        }

        composeTestRule.onNodeWithTag("swipe_hint_indicator").assertIsDisplayed()

        composeTestRule.onNodeWithTag("card_container").performTouchInput {
            swipeLeft()
        }

        assertTrue(hintDismissed)
        composeTestRule.onNodeWithTag("swipe_hint_indicator").assertDoesNotExist()

        composeTestRule.onNodeWithTag("card_container").performClick()
        assertTrue(buttonClicked)
    }
}
