package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SimLimitValidatorLocaleTest {
    @Test
    fun `test validate with ar digits`() {
        val arLocale = Locale.forLanguageTag("ar")
        // Arabic-Indic digits: ١٠٠ (100) and ١৫.৫ (15.5)
        val result =
            SimLimitValidator.validate(
                minutesText = "\u0661\u0660\u0660",
                trafficText = "\u0661\u0665\u066B\u0665",
                fieldRequiredMsg = "required",
                invalidNumberMsg = "invalid",
                minutesRangeMsg = "range",
                trafficRangeMsg = "range",
                locale = arLocale,
            )

        assertTrue("Result should be Valid", result is SimLimitValidationResult.Valid)
        val valid = result as SimLimitValidationResult.Valid
        assert(valid.minutes == 100)
        assert(valid.trafficGb == 15.5f)
    }

    @Test
    fun `test validate with bn digits`() {
        val bnLocale = Locale.forLanguageTag("bn")
        // Bengali digits: ১০০ (100) and ১৫.৫ (15.5)
        val result =
            SimLimitValidator.validate(
                minutesText = "\u09E7\u09E6\u09E6",
                trafficText = "\u09E7\u09EB.\u09EB",
                fieldRequiredMsg = "required",
                invalidNumberMsg = "invalid",
                minutesRangeMsg = "range",
                trafficRangeMsg = "range",
                locale = bnLocale,
            )

        assertTrue("Result should be Valid", result is SimLimitValidationResult.Valid)
        val valid = result as SimLimitValidationResult.Valid
        assert(valid.minutes == 100)
        assert(valid.trafficGb == 15.5f)
    }
}
