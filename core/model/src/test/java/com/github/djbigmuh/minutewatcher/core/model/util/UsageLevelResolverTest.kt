package com.github.djbigmuh.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageLevelResolverTest {
    @Test
    fun `resolve Long limit zero or negative returns NORMAL`() {
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 100L, limit = 0L))
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 100L, limit = -10L))
    }

    @Test
    fun `resolve Long remaining zero or negative returns CRITICAL`() {
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(remaining = 0L, limit = 100L))
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(remaining = -5L, limit = 100L))
    }

    @Test
    fun `resolve Long remaining below critical threshold returns CRITICAL`() {
        // Critical threshold is 10%
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(remaining = 9L, limit = 100L))
    }

    @Test
    fun `resolve Long remaining in low threshold range returns LOW`() {
        // Low threshold is 20% (between 10% and 20%)
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(remaining = 10L, limit = 100L))
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(remaining = 15L, limit = 100L))
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(remaining = 19L, limit = 100L))
    }

    @Test
    fun `resolve Long remaining above low threshold returns NORMAL`() {
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 20L, limit = 100L))
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 80L, limit = 100L))
    }

    @Test
    fun `resolve Float limit zero or negative or NaN returns NORMAL`() {
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 100f, limit = 0f))
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 100f, limit = -10f))
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 100f, limit = Float.NaN))
    }

    @Test
    fun `resolve Float remaining zero or negative or NaN returns CRITICAL`() {
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(remaining = 0f, limit = 100f))
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(remaining = -5f, limit = 100f))
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(remaining = Float.NaN, limit = 100f))
    }

    @Test
    fun `resolve Float remaining below critical threshold returns CRITICAL`() {
        assertEquals(UsageStatus.CRITICAL, UsageLevelResolver.resolve(remaining = 9f, limit = 100f))
    }

    @Test
    fun `resolve Float remaining in low threshold range returns LOW`() {
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(remaining = 10f, limit = 100f))
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(remaining = 15f, limit = 100f))
        assertEquals(UsageStatus.LOW, UsageLevelResolver.resolve(remaining = 19f, limit = 100f))
    }

    @Test
    fun `resolve Float remaining at or above low threshold returns NORMAL`() {
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 20f, limit = 100f))
        assertEquals(UsageStatus.NORMAL, UsageLevelResolver.resolve(remaining = 80f, limit = 100f))
    }

    @Test
    fun `UsageColors returns correct color for status and theme`() {
        assertEquals(UsageColors.normal(true), UsageColors.get(UsageStatus.NORMAL, isDarkBackground = true))
        assertEquals(UsageColors.normal(false), UsageColors.get(UsageStatus.NORMAL, isDarkBackground = false))
        assertEquals(UsageColors.low(true), UsageColors.get(UsageStatus.LOW, isDarkBackground = true))
        assertEquals(UsageColors.low(false), UsageColors.get(UsageStatus.LOW, isDarkBackground = false))
        assertEquals(UsageColors.critical(true), UsageColors.get(UsageStatus.CRITICAL, isDarkBackground = true))
        assertEquals(UsageColors.critical(false), UsageColors.get(UsageStatus.CRITICAL, isDarkBackground = false))
    }

    @Test
    fun `WidgetDimens constants verification`() {
        assertEquals(14, WidgetDimens.SIZE_ICON_SINGLE)
        assertEquals(16, WidgetDimens.SIZE_ICON_MULTI)
        assertEquals(10f, WidgetDimens.FONT_SINGLE_NAME, 0.001f)
        assertEquals(12f, WidgetDimens.FONT_SINGLE_VALUE, 0.001f)
        assertEquals(12f, WidgetDimens.FONT_MULTI_NAME, 0.001f)
        assertEquals(14f, WidgetDimens.FONT_MULTI_VALUE, 0.001f)
        assertEquals(11f, WidgetDimens.FONT_LIMIT, 0.001f)
        assertEquals(11f, WidgetDimens.FONT_FULL, 0.001f)
        assertEquals(4, WidgetDimens.SPACING_DEFAULT)
        assertEquals(2, WidgetDimens.SPACING_SMALL)
    }
}
