package com.example.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutModeTest {

    @Test
    fun testFromValue() {
        assertEquals(WidgetLayoutMode.SINGLE_LINE, WidgetLayoutMode.fromValue(0))
        assertEquals(WidgetLayoutMode.TWO_LINES, WidgetLayoutMode.fromValue(1))
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(2))
        assertEquals(WidgetLayoutMode.SIX_LINES, WidgetLayoutMode.fromValue(3))
        assertEquals(WidgetLayoutMode.SIMPLE, WidgetLayoutMode.fromValue(4))
    }

    @Test
    fun testFromValue_Default() {
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(-1))
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(100))
    }
}
