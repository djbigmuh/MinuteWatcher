package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import com.github.djbigmuh.minutewatcher.core.model.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SimLimitValidatorTest {
    private val required = "Required"
    private val invalid = "Invalid"
    private val range = "Range"
    private val defaultLocale = Locale.US

    @Test
    fun `validate returns errors for empty strings`() {
        val result =
            SimLimitValidator.validate(
                "",
                "",
                required,
                invalid,
                range,
                range,
                defaultLocale,
            ) as SimLimitValidationResult.Invalid
        assertEquals(required, result.minutesError)
        assertEquals(required, result.trafficError)
    }

    @Test
    fun `validate returns required error for whitespace only strings`() {
        val result =
            SimLimitValidator.validate(
                "   ",
                "   ",
                required,
                invalid,
                range,
                range,
                defaultLocale,
            ) as SimLimitValidationResult.Invalid
        assertEquals(required, result.minutesError)
        assertEquals(required, result.trafficError)
    }

    @Test
    fun `validate parses Germany grouping separator in minutes`() {
        val result =
            SimLimitValidator.validate(
                "1.234",
                "10",
                required,
                invalid,
                range,
                range,
                Locale.GERMANY,
            )
        assertTrue("Result should be Valid", result is SimLimitValidationResult.Valid)
        val valid = result as SimLimitValidationResult.Valid
        assertEquals(1234, valid.minutes)
        assertEquals(10f, valid.trafficGb)
    }

    @Test
    fun `validate returns errors for invalid numbers`() {
        val result =
            SimLimitValidator.validate(
                "abc",
                "1.2.3",
                required,
                invalid,
                range,
                range,
                defaultLocale,
            ) as SimLimitValidationResult.Invalid
        assertEquals(invalid, result.minutesError)
        assertEquals(invalid, result.trafficError)
    }

    @Test
    fun `validate returns errors for out of range numbers`() {
        val result =
            SimLimitValidator.validate(
                (Constants.MAX_MINUTES_LIMIT + 1).toString(),
                "1000000",
                required,
                invalid,
                range,
                range,
                defaultLocale,
            ) as SimLimitValidationResult.Invalid
        assertEquals(range, result.minutesError)
        assertEquals(range, result.trafficError)
    }

    @Test
    fun `validate returns values for valid input`() {
        val result = SimLimitValidator.validate("100", "1.5", required, invalid, range, range, defaultLocale)
        val valid = result as SimLimitValidationResult.Valid
        assertEquals(100, valid.minutes)
        assertEquals(1.5f, valid.trafficGb)
    }

    @Test
    fun `validate rejects NaN input`() {
        val result =
            SimLimitValidator.validate(
                "100",
                "NaN",
                required,
                invalid,
                range,
                range,
                defaultLocale,
            ) as SimLimitValidationResult.Invalid
        assertEquals(invalid, result.trafficError)
    }

    @Test
    fun `validate rejects Infinity input`() {
        val result =
            SimLimitValidator.validate(
                "100",
                "Infinity",
                required,
                invalid,
                range,
                range,
                defaultLocale,
            ) as SimLimitValidationResult.Invalid
        assertEquals(invalid, result.trafficError)
    }
}
