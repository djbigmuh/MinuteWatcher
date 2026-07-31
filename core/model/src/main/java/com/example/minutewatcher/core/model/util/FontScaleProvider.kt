package com.example.minutewatcher.core.model.util

/**
 * Provides font size multipliers based on user settings.
 */
object FontScaleProvider {
    private const val SCALE_MINUS_TWO = 0.8f
    private const val SCALE_MINUS_ONE = 0.9f
    private const val SCALE_NORMAL = 1.0f
    private const val SCALE_PLUS_ONE = 1.1f
    private const val SCALE_PLUS_TWO = 1.2f

    fun getMultiplier(fontSizeSetting: Int): Float =
        when (fontSizeSetting) {
            0 -> SCALE_MINUS_TWO
            1 -> SCALE_MINUS_ONE
            2 -> SCALE_NORMAL
            3 -> SCALE_PLUS_ONE
            4 -> SCALE_PLUS_TWO
            else -> SCALE_NORMAL
        }
}
