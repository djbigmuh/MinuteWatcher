package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import com.github.djbigmuh.minutewatcher.core.model.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
class AdjustmentValidatorTest(
    private val locale: Locale,
    private val decimalSeparator: String,
) {
    private val invalidNumberMsg = "Invalid number"
    private val invalidRangeMsg = "Invalid range"

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "locale={0}")
        fun data(): Collection<Array<Any>> =
            listOf(
                arrayOf(Locale.US, "."),
                arrayOf(Locale.GERMANY, ","),
                arrayOf(Locale.forLanguageTag("ru-RU"), ","),
            )
    }

    @Test
    fun `minutes mode accepts integer value`() {
        val result =
            AdjustmentValidator.validate(
                text = "100",
                isMinutes = true,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Result should be Valid", result is AdjustmentValidationResult.Valid)
        assertEquals(100f, (result as AdjustmentValidationResult.Valid).value)
    }

    @Test
    fun `minutes mode rejects fractional value`() {
        val fractionalText = "100" + decimalSeparator + "7"

        val result =
            AdjustmentValidator.validate(
                text = fractionalText,
                isMinutes = true,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue(
            "Fractional value in minutes mode should be Invalid",
            result is AdjustmentValidationResult.Invalid,
        )
        assertEquals(invalidNumberMsg, (result as AdjustmentValidationResult.Invalid).errorText)
    }

    @Test
    fun `minutes mode rejects empty string`() {
        val result =
            AdjustmentValidator.validate(
                text = "",
                isMinutes = true,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Empty string should be Invalid", result is AdjustmentValidationResult.Invalid)
        assertEquals(invalidNumberMsg, (result as AdjustmentValidationResult.Invalid).errorText)
    }

    @Test
    fun `minutes mode rejects negative value`() {
        val result =
            AdjustmentValidator.validate(
                text = "-5",
                isMinutes = true,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Negative value should be Invalid range", result is AdjustmentValidationResult.Invalid)
        assertEquals(invalidRangeMsg, (result as AdjustmentValidationResult.Invalid).errorText)
    }

    @Test
    fun `minutes mode rejects value above max limit`() {
        val overMax = (Constants.MAX_MINUTES_LIMIT + 1).toString()
        val result =
            AdjustmentValidator.validate(
                text = overMax,
                isMinutes = true,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Value above max limit should be Invalid range", result is AdjustmentValidationResult.Invalid)
        assertEquals(invalidRangeMsg, (result as AdjustmentValidationResult.Invalid).errorText)
    }

    @Test
    fun `minutes mode ignores group separators`() {
        val textWithGrouping = if (decimalSeparator == ".") "1,000" else "1.000"
        val result =
            AdjustmentValidator.validate(
                text = textWithGrouping,
                isMinutes = true,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Result should be Valid with group separators ignored", result is AdjustmentValidationResult.Valid)
        assertEquals(1000f, (result as AdjustmentValidationResult.Valid).value)
    }

    @Test
    fun `traffic mode accepts fractional value`() {
        val text = "1" + decimalSeparator + "5"
        val result =
            AdjustmentValidator.validate(
                text = text,
                isMinutes = false,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Fractional traffic value should be Valid", result is AdjustmentValidationResult.Valid)
        assertEquals(1.5f, (result as AdjustmentValidationResult.Valid).value)
    }

    @Test
    fun `traffic mode rejects empty string`() {
        val result =
            AdjustmentValidator.validate(
                text = "",
                isMinutes = false,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Empty string should be Invalid", result is AdjustmentValidationResult.Invalid)
        assertEquals(invalidNumberMsg, (result as AdjustmentValidationResult.Invalid).errorText)
    }

    @Test
    fun `traffic mode rejects negative value`() {
        val result =
            AdjustmentValidator.validate(
                text = "-5",
                isMinutes = false,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue("Negative traffic value should be Invalid range", result is AdjustmentValidationResult.Invalid)
        assertEquals(invalidRangeMsg, (result as AdjustmentValidationResult.Invalid).errorText)
    }

    @Test
    fun `traffic mode rejects value exceeding max limit`() {
        val overMax = (Constants.MAX_TRAFFIC_LIMIT_GB + 10f).toString()
        val result =
            AdjustmentValidator.validate(
                text = overMax,
                isMinutes = false,
                locale = locale,
                invalidNumberMsg = invalidNumberMsg,
                invalidRangeMsg = invalidRangeMsg,
            )
        assertTrue(
            "Value above max traffic limit should be Invalid range",
            result is AdjustmentValidationResult.Invalid,
        )
        assertEquals(invalidRangeMsg, (result as AdjustmentValidationResult.Invalid).errorText)
    }
}
