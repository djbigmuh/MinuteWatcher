package com.example.minutewatcher.core.model.util

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
}
