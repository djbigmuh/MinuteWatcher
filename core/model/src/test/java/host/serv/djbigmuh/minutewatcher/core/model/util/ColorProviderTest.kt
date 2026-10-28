package host.serv.djbigmuh.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorProviderTest {
    @Test
    fun `getUsageStatus returns NORMAL when limit is zero`() {
        assertEquals(UsageStatus.NORMAL, ColorProvider.getUsageStatus(10f, 0f))
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(10L, 0L))
    }

    @Test
    fun `getUsageStatus returns CRITICAL when remaining is zero or less`() {
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(0f, 100f))
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(-1f, 100f))
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(0L, 100L))
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(-1L, 100L))
    }

    @Test
    fun `getUsageStatus returns CRITICAL when remaining is below critical threshold`() {
        // Critical threshold is 10% of 100 is 10 (strict < 10)
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(9f, 100f))
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(9L, 100L))
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(10L, 100L))
    }

    @Test
    fun `UsageLevelResolver threshold boundaries`() {
        // limit = 1000L
        // Critical threshold (10%) = 100L. Strictly less than 100L (< 100) -> CRITICAL (e.g. 99L). At 100L -> LOW.
        // Low threshold (20%) = 200L. Strictly less than 200L (< 200) -> LOW (e.g. 199L). At 200L -> NORMAL.

        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(99L, 1000L))
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(100L, 1000L))
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(199L, 1000L))
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(200L, 1000L))
    }

    @Test
    fun `UsageLevelResolver precision with large byte values`() {
        // 10 GB limit = 10L * 1024 * 1024 * 1024 bytes = 10,737,418,240 bytes
        val limitBytes = 10L * 1024 * 1024 * 1024
        // 5% remaining (critical) = limitBytes / 20
        val criticalBytes = limitBytes / 20L
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(criticalBytes, limitBytes))

        // 15% remaining (low) = limitBytes * 15 / 100
        val lowBytes = limitBytes * 15L / 100L
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(lowBytes, limitBytes))

        // 25% remaining (normal) = limitBytes / 4
        val normalBytes = limitBytes / 4L
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(normalBytes, limitBytes))
    }

    @Test
    fun `getUsageStatus correctly handles fractional boundaries`() {
        // 4.0 out of 20.5 is 19.5% remaining, which is strictly less than 20% -> LOW
        assertEquals(UsageStatus.LOW, ColorProvider.getUsageStatus(4.0f, 20.5f))
    }

    @Test
    fun `getUsageStatus respects exact percentage boundaries on binary exact values`() {
        // 2.0 / 20.0 = 10% -> LOW (since < 10% is CRITICAL, exactly 10% is LOW)
        assertEquals(UsageStatus.LOW, ColorProvider.getUsageStatus(2.0f, 20.0f))
        // 4.0 / 20.0 = 20% -> NORMAL (since < 20% is LOW, exactly 20% is NORMAL)
        assertEquals(UsageStatus.NORMAL, ColorProvider.getUsageStatus(4.0f, 20.0f))
        // 1.0 / 20.0 = 5% -> CRITICAL (< 10%)
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(1.0f, 20.0f))
        // 0.5 / 20.0 = 2.5% -> CRITICAL (< 10%)
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(0.5f, 20.0f))
    }

    @Test
    fun `getUsageStatus matches integer path resolution for integer pairs`() {
        val pairs =
            listOf(
                Pair(10f, 0f),
                Pair(0f, 100f),
                Pair(-1f, 100f),
                Pair(9f, 100f),
                Pair(10f, 100f),
                Pair(199f, 1000f),
                Pair(200f, 1000f),
            )
        for ((rem, lim) in pairs) {
            val floatResult = ColorProvider.getUsageStatus(rem, lim)
            val longResult = UsageLevelResolver.resolve(rem.toLong(), lim.toLong())
            assertEquals(longResult, floatResult)
        }
    }

    @Test
    fun `getUsageStatus handles degenerate float inputs`() {
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(Float.NaN, 100f))
        assertEquals(UsageStatus.NORMAL, ColorProvider.getUsageStatus(10f, Float.NaN))
        assertEquals(UsageStatus.NORMAL, ColorProvider.getUsageStatus(Float.POSITIVE_INFINITY, 100f))
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(Float.NEGATIVE_INFINITY, 100f))
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(10f, Float.POSITIVE_INFINITY))
    }
}
