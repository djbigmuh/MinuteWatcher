package com.example.minutewatcher.feature.main.ui.components

import com.example.minutewatcher.core.model.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimLimitValidatorTest {

    private val required = "Required"
    private val invalid = "Invalid"
    private val range = "Range"

    @Test
    fun `validate returns errors for empty strings`() {
        val result = SimLimitValidator.validate("", "", required, invalid, range, range)
        assertEquals(required, result.minutesError)
        assertEquals(required, result.trafficError)
    }

    @Test
    fun `validate returns errors for invalid numbers`() {
        val result = SimLimitValidator.validate("abc", "1.2.3", required, invalid, range, range)
        assertEquals(invalid, result.minutesError)
        assertEquals(invalid, result.trafficError)
    }

    @Test
    fun `validate returns errors for out of range numbers`() {
        val result = SimLimitValidator.validate((Constants.MAX_MINUTES_LIMIT + 1).toString(), "1000000", required, invalid, range, range)
        assertEquals(range, result.minutesError)
        assertEquals(range, result.trafficError)
    }

    @Test
    fun `validate returns values for valid input`() {
        val result = SimLimitValidator.validate("100", "1.5", required, invalid, range, range)
        assertNull(result.minutesError)
        assertNull(result.trafficError)
        assertEquals(100, result.minutes)
        assertEquals(1.5f, result.trafficGb)
    }
}
