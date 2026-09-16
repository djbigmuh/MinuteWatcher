package com.example.minutewatcher.feature.main.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class SimLimitValidatorLocaleTest {

    @Test
    fun `test validate with ar digits`() {
        // Arabic-Indic digits: ١٠٠ (100) and ١٥٫٥ (15.5)
        val result = SimLimitValidator.validate(
            minutesText = "\u0661\u0660\u0660",
            trafficText = "\u0661\u0665\u066B\u0665",
            fieldRequiredMsg = "required",
            invalidNumberMsg = "invalid",
            minutesRangeMsg = "range",
            trafficRangeMsg = "range"
        )

        assertTrue("Result should be Valid", result is SimLimitValidationResult.Valid)
        val valid = result as SimLimitValidationResult.Valid
        assert(valid.minutes == 100)
        assert(valid.trafficGb == 15.5f)
    }
}
