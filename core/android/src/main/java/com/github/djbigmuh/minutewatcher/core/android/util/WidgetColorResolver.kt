package com.github.djbigmuh.minutewatcher.core.android.util

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.util.UsageColors
import com.github.djbigmuh.minutewatcher.core.model.util.UsageStatus

/**
 * Resolves widget colors.
 * Contains methods for resolving text color, status color,
 * and "No data" color.
 */
object WidgetColorResolver {
    private fun isDarkBackground(luminance: Double): Boolean = luminance <= Constants.UI.LUMINANCE_THRESHOLD

    fun resolveTextColors(backgroundLuminance: Double): Pair<Int, Int> =
        if (isDarkBackground(backgroundLuminance)) {
            Color.WHITE to Color.LTGRAY
        } else {
            Color.BLACK to Color.DKGRAY
        }

    fun getStatusColor(
        status: UsageStatus,
        backgroundLuminance: Double,
    ): Int = UsageColors.get(status, isDarkBackground(backgroundLuminance))

    fun resolveNoDataColor(backgroundColor: Int): Int {
        val luminance = ColorUtils.calculateLuminance(backgroundColor)
        return if (isDarkBackground(luminance)) Color.WHITE else Color.BLACK
    }
}
