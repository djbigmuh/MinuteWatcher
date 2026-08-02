package com.example.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorProviderTest {

    @Test
    fun `getUsageStatus returns NORMAL when limit is zero`() {
        assertEquals(UsageStatus.NORMAL, ColorProvider.getUsageStatus(10f, 0f))
    }

    @Test
    fun `getUsageStatus returns CRITICAL when remaining is zero or less`() {
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(0f, 100f))
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(-1f, 100f))
    }

    @Test
    fun `getUsageStatus returns CRITICAL when remaining is below critical threshold`() {
        // Critical threshold is 10%
        assertEquals(UsageStatus.CRITICAL, ColorProvider.getUsageStatus(9f, 100f))
    }

    @Test
    fun `getUsageStatus returns LOW when remaining is below low threshold but above critical`() {
        // Low threshold is 20%, Critical is 10%
        assertEquals(UsageStatus.LOW, ColorProvider.getUsageStatus(15f, 100f))
        assertEquals(UsageStatus.LOW, ColorProvider.getUsageStatus(19f, 100f))
    }

    @Test
    fun `getUsageStatus returns NORMAL when remaining is above low threshold`() {
        assertEquals(UsageStatus.NORMAL, ColorProvider.getUsageStatus(21f, 100f))
        assertEquals(UsageStatus.NORMAL, ColorProvider.getUsageStatus(50f, 100f))
    }
}
