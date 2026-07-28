package com.example.minutewatcher.core.model.util

object UsageColors {
    fun normal(isDarkBackground: Boolean): Int =
        if (isDarkBackground) 0xFF6BCB77.toInt() else 0xFF388E3C.toInt()

    fun low(isDarkBackground: Boolean): Int =
        if (isDarkBackground) 0xFFFFD93D.toInt() else 0xFFF57C00.toInt()

    fun critical(isDarkBackground: Boolean): Int =
        if (isDarkBackground) 0xFFFF6B6B.toInt() else 0xFFD32F2F.toInt()

    fun get(
        status: UsageStatus,
        isDarkBackground: Boolean,
    ): Int =
        when (status) {
            UsageStatus.NORMAL -> normal(isDarkBackground)
            UsageStatus.LOW -> low(isDarkBackground)
            UsageStatus.CRITICAL -> critical(isDarkBackground)
        }
}
