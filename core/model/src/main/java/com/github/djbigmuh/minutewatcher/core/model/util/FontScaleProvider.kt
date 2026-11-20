package com.github.djbigmuh.minutewatcher.core.model.util

/**
 * Provides font size multipliers based on user settings.
 */
object FontScaleProvider {
    private const val FONT_SETTING_MINUS_TWO = 0
    private const val FONT_SETTING_MINUS_ONE = 1
    private const val FONT_SETTING_NORMAL = 2
    private const val FONT_SETTING_PLUS_ONE = 3
    private const val FONT_SETTING_PLUS_TWO = 4

    private const val SCALE_MINUS_TWO = 0.8f
    private const val SCALE_MINUS_ONE = 0.9f
    private const val SCALE_NORMAL = 1.0f
    private const val SCALE_PLUS_ONE = 1.1f
    private const val SCALE_PLUS_TWO = 1.2f

    fun getMultiplier(fontSizeSetting: Int): Float =
        when (fontSizeSetting) {
            FONT_SETTING_MINUS_TWO -> SCALE_MINUS_TWO
            FONT_SETTING_MINUS_ONE -> SCALE_MINUS_ONE
            FONT_SETTING_NORMAL -> SCALE_NORMAL
            FONT_SETTING_PLUS_ONE -> SCALE_PLUS_ONE
            FONT_SETTING_PLUS_TWO -> SCALE_PLUS_TWO
            else -> SCALE_NORMAL
        }
}
