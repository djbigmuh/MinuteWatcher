package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.DecimalFormatSymbols

@RunWith(AndroidJUnit4::class)
class DialogsTest {
    @get:Rule
    val composeTestRule =
        createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun simLimitDialogInputValidation() {
        var onConfirmCalled = false
        val setLimitText = context.getString(R.string.set_minutes_limit)
        val setButtonText = context.getString(R.string.set_button)
        val validationErrorText = context.getString(R.string.validation_error_invalid_number)

        composeTestRule.setContent {
            SimLimitComposeDialog(
                slot = 0,
                operatorName = "MTS",
                initialMinutes = 0,
                initialTrafficMb = 0,
                onDismiss = {},
                onConfirm = { _, _, _ -> onConfirmCalled = true },
                onReset = {},
            )
        }

        // Minutes field with invalid alphanumeric string
        composeTestRule
            .onNodeWithText(setLimitText, substring = true, ignoreCase = true)
            .performTextInput("abc123xyz")

        composeTestRule
            .onNodeWithText(setButtonText, ignoreCase = true)
            .performClick()

        assertFalse("onConfirm must not be called when input contains invalid alphanumeric characters", onConfirmCalled)
        composeTestRule
            .onNodeWithText(validationErrorText)
            .assertIsDisplayed()
    }

    @Test
    fun simLimitDialogDecimalTrafficInput() {
        var capturedTrafficMb = -1
        val setTrafficText = context.getString(R.string.set_traffic_limit)
        val setButtonText = context.getString(R.string.set_button)

        composeTestRule.setContent {
            SimLimitComposeDialog(
                slot = 0,
                operatorName = "MTS",
                initialMinutes = 0,
                initialTrafficMb = 0,
                onDismiss = {},
                onConfirm = { _, traffic, _ -> capturedTrafficMb = traffic },
                onReset = {},
            )
        }

        val locale =
            context.resources.configuration.locales
                .get(0)
        val decimalSeparator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val trafficInput = "1" + decimalSeparator + "5"

        // Traffic field with locale-aware decimal separator
        composeTestRule
            .onNodeWithText(setTrafficText, substring = true, ignoreCase = true)
            .performTextInput(trafficInput)

        composeTestRule
            .onNodeWithText(setButtonText, ignoreCase = true)
            .performClick()

        // 1.5 GB * 1024 = 1536 MB
        assertEquals(1536, capturedTrafficMb)
    }
}
