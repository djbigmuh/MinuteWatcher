package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import host.serv.djbigmuh.minutewatcher.core.common.util.NumberInputNormalizer
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import java.util.Locale

sealed interface AdjustmentValidationResult {
    data class Valid(
        val value: Float,
    ) : AdjustmentValidationResult

    data class Invalid(
        val errorText: String,
    ) : AdjustmentValidationResult
}

object AdjustmentValidator {
    fun validate(
        text: String,
        isMinutes: Boolean,
        locale: Locale,
        invalidNumberMsg: String,
        invalidRangeMsg: String,
    ): AdjustmentValidationResult {
        if (text.isBlank()) {
            return AdjustmentValidationResult.Invalid(invalidNumberMsg)
        }

        val normalized = NumberInputNormalizer.normalize(text, locale)

        return if (isMinutes) {
            val value = normalized.toIntOrNull()
            when {
                value == null -> AdjustmentValidationResult.Invalid(invalidNumberMsg)
                value < 0 || value > Constants.MAX_MINUTES_LIMIT -> AdjustmentValidationResult.Invalid(invalidRangeMsg)
                else -> AdjustmentValidationResult.Valid(value.toFloat())
            }
        } else {
            val value = normalized.toFloatOrNull()
            when {
                value == null || value.isNaN() || value.isInfinite() -> {
                    AdjustmentValidationResult.Invalid(invalidNumberMsg)
                }

                value < 0f || value > Constants.MAX_TRAFFIC_LIMIT_GB -> {
                    AdjustmentValidationResult.Invalid(invalidRangeMsg)
                }

                else -> AdjustmentValidationResult.Valid(value)
            }
        }
    }
}
