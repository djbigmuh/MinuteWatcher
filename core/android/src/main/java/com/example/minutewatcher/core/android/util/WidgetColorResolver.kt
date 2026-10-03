package com.example.minutewatcher.core.android.util

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.UsageColors
import com.example.minutewatcher.core.model.util.UsageStatus

/**
 * Resolves widget colors.
 * Contains methods for resolving text color, status color,
 * and "No data" color.
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
        val luminance = ColorUtils.calculateLuminance(backgroundColor)
        return if (luminance > Constants.UI.LUMINANCE_THRESHOLD) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }
}
