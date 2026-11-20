package com.github.djbigmuh.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutModeTest {
    @Test
    fun `fromValue when valid ordinal then returns correct mode`() {
        assertEquals(WidgetLayoutMode.SINGLE_LINE, WidgetLayoutMode.fromValue(0))
        assertEquals(WidgetLayoutMode.TWO_LINES, WidgetLayoutMode.fromValue(1))
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(2))
        assertEquals(WidgetLayoutMode.SIX_LINES, WidgetLayoutMode.fromValue(3))
        assertEquals(WidgetLayoutMode.SIMPLE, WidgetLayoutMode.fromValue(4))
    }

    @Test
    fun `fromValue when invalid ordinal then returns default mode`() {
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(-1))
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(100))
    }
}
