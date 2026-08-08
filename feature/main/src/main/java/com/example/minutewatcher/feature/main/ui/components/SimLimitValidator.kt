package com.example.minutewatcher.feature.main.ui.components

import com.example.minutewatcher.core.model.Constants

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
    ): SimLimitValidationResult {
        val min = minutesText.toIntOrNull()
        val trafGb = trafficText.toFloatOrNull()

        var minErr: String? = null
        if (minutesText.isEmpty()) {
            minErr = fieldRequiredMsg
        } else if (min == null) {
            minErr = invalidNumberMsg
        } else if (min < 0 || min > Constants.MAX_MINUTES_LIMIT) {
            minErr = minutesRangeMsg
        }

        var trafErr: String? = null
        if (trafficText.isEmpty()) {
            trafErr = fieldRequiredMsg
        } else if (trafGb == null) {
            trafErr = invalidNumberMsg
        } else if (trafGb.isNaN() || trafGb.isInfinite()) {
            trafErr = invalidNumberMsg
        } else if (trafGb < 0 || trafGb > Constants.MAX_TRAFFIC_LIMIT_GB) {
            trafErr = trafficRangeMsg
        }

        val isInputValid = minErr == null && trafErr == null
        val areValuesNotNull = min != null && trafGb != null
        return if (isInputValid && areValuesNotNull) {
            SimLimitValidationResult.Valid(min, trafGb)
        } else {
            SimLimitValidationResult.Invalid(minErr, trafErr)
        }
    }
}
