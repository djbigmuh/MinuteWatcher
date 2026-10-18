package host.serv.djbigmuh.minutewatcher.core.model.util

object UsageColors {
    private const val NORMAL_DARK = 0xFF6BCB77.toInt()
    private const val NORMAL_LIGHT = 0xFF388E3C.toInt()
    private const val LOW_DARK = 0xFFFFD93D.toInt()
    private const val LOW_LIGHT = 0xFFF57C00.toInt()
    private const val CRITICAL_DARK = 0xFFFF6B6B.toInt()
    private const val CRITICAL_LIGHT = 0xFFD32F2F.toInt()

    fun normal(isDarkBackground: Boolean): Int = if (isDarkBackground) NORMAL_DARK else NORMAL_LIGHT

    fun low(isDarkBackground: Boolean): Int = if (isDarkBackground) LOW_DARK else LOW_LIGHT

    fun critical(isDarkBackground: Boolean): Int = if (isDarkBackground) CRITICAL_DARK else CRITICAL_LIGHT

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
