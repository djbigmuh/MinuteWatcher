package com.example.minutewatcher.core.android.util

import android.graphics.Color
import com.example.minutewatcher.core.model.util.UsageColors
import com.example.minutewatcher.core.model.util.UsageStatus

object WidgetColorResolver {
    fun resolveTextColors(backgroundLuminance: Double): Pair<Int, Int> {
        val textColor = if (backgroundLuminance > 0.5f) Color.BLACK else Color.WHITE
        val secondaryTextColor = if (backgroundLuminance > 0.5f) Color.DKGRAY else Color.LTGRAY
        return textColor to secondaryTextColor
    }

    fun getStatusColor(status: UsageStatus, backgroundLuminance: Double): Int {
        val isDark = backgroundLuminance < 0.5
        return UsageColors.get(status, isDark)
    }
}
