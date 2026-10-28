package host.serv.djbigmuh.minutewatcher.feature.main

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import host.serv.djbigmuh.minutewatcher.feature.main.controller.OperationError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class SimCardErrorHostTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `operation error displays snackbar and triggers dismiss callback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedText = context.getString(R.string.error_operation_failed_retry)
        var dismissed = false

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                    SimCardErrorHost(
                        snackbarHostState = snackbarHostState,
                        lastOperationError =
                            OperationError(
                                messageRes = R.string.error_operation_failed_retry,
                                slot = 0,
                            ),
                        onDismissOperationError = { dismissed = true },
                    )
                }
            }
        }

        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(expectedText).assertIsDisplayed()

        // Advance past snackbar duration (4000ms + buffer)
        composeTestRule.mainClock.advanceTimeBy(5000L)
        composeTestRule.waitForIdle()

        assertTrue(dismissed)
    }

    @Test
    fun `null operation error displays nothing and does not trigger callback`() {
        var dismissed = false

        composeTestRule.setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            Scaffold(
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            ) { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                    SimCardErrorHost(
                        snackbarHostState = snackbarHostState,
                        lastOperationError = null,
                        onDismissOperationError = { dismissed = true },
                    )
                }
            }
        }

        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        assertFalse(dismissed)
    }
}
