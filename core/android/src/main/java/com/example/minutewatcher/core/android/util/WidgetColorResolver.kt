package com.example.minutewatcher.core.android.util

import android.graphics.Color
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.UsageColors
import com.example.minutewatcher.core.model.util.UsageStatus

/**
 * Разрешает цвета виджета.
 * Содержит методы для разрешения цвета текста, цвета статуса
 * и цвета "Нет данных".
 */
object WidgetColorResolver {
    fun resolveTextColors(backgroundLuminance: Double): Pair<Int, Int> {
        val textColor = if (backgroundLuminance > Constants.UI.LUMINANCE_THRESHOLD) Color.BLACK else Color.WHITE
        val secondaryTextColor =
            if (backgroundLuminance >
                Constants.UI.LUMINANCE_THRESHOLD
            ) {
                Color.DKGRAY
            } else {
                Color.LTGRAY
            }
        return textColor to secondaryTextColor
    }

    fun getStatusColor(
        status: UsageStatus,
        backgroundLuminance: Double,
    ): Int {
        val isDark = backgroundLuminance < Constants.UI.LUMINANCE_THRESHOLD
        return UsageColors.get(status, isDark)
    }

    fun resolveNoDataColor(backgroundColor: Int): Int {
        val luminance =
            androidx.core.graphics.ColorUtils
                .calculateLuminance(backgroundColor)
        return if (luminance > Constants.UI.LUMINANCE_THRESHOLD) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }
}
