package com.example.minutewatcher.feature.main.ui.components

import com.example.minutewatcher.core.common.util.NumberInputNormalizer
import com.example.minutewatcher.core.model.Constants
import java.util.Locale

sealed interface SimLimitValidationResult {
    data class Valid(
        val minutes: Int,
        val trafficGb: Float,
    ) : SimLimitValidationResult

    data class Invalid(
        val minutesError: String?,
        val trafficError: String?,
    ) : SimLimitValidationResult
}

object SimLimitValidator {
    fun validate(
        minutesText: String,
        trafficText: String,
        fieldRequiredMsg: String,
        invalidNumberMsg: String,
        minutesRangeMsg: String,
        trafficRangeMsg: String,
        locale: Locale,
    ): SimLimitValidationResult {
        val normalizedMin = NumberInputNormalizer.normalize(minutesText, locale)
        val normalizedTraf = NumberInputNormalizer.normalize(trafficText, locale)

        val min = normalizedMin.toIntOrNull()
        val trafGb = normalizedTraf.toFloatOrNull()

        val minErr = validateMinutes(minutesText, min, fieldRequiredMsg, invalidNumberMsg, minutesRangeMsg)
        val trafErr = validateTraffic(trafficText, trafGb, fieldRequiredMsg, invalidNumberMsg, trafficRangeMsg)

        val isValid = minErr == null && trafErr == null
        return if (isValid && min != null && trafGb != null) {
            SimLimitValidationResult.Valid(min, trafGb)
        } else {
            SimLimitValidationResult.Invalid(minErr, trafErr)
        }
    }

    private fun validateMinutes(
        text: String,
        value: Int?,
        fieldRequiredMsg: String,
        invalidNumberMsg: String,
        rangeMsg: String,
    ): String? =
        when {
            text.isBlank() -> fieldRequiredMsg
            value == null -> invalidNumberMsg
            value < 0 || value > Constants.MAX_MINUTES_LIMIT -> rangeMsg
            else -> null
        }

    private fun validateTraffic(
        text: String,
        value: Float?,
        fieldRequiredMsg: String,
        invalidNumberMsg: String,
        rangeMsg: String,
    ): String? =
        when {
            text.isBlank() -> fieldRequiredMsg
            value == null || value.isNaN() || value.isInfinite() -> invalidNumberMsg
            value < 0 || value > Constants.MAX_TRAFFIC_LIMIT_GB -> rangeMsg
            else -> null
        }
}
