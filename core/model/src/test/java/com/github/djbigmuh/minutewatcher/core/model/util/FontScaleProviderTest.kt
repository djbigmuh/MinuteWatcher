package com.github.djbigmuh.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FontScaleProviderTest {
    @Test
    fun `getMultiplier returns correct values for settings`() {
        assertEquals(0.8f, FontScaleProvider.getMultiplier(0), 0.001f)
        assertEquals(0.9f, FontScaleProvider.getMultiplier(1), 0.001f)
        assertEquals(1.0f, FontScaleProvider.getMultiplier(2), 0.001f)
        assertEquals(1.1f, FontScaleProvider.getMultiplier(3), 0.001f)
        assertEquals(1.2f, FontScaleProvider.getMultiplier(4), 0.001f)
    }

    @Test
    fun `getMultiplier returns NORMAL for unknown settings`() {
        assertEquals(1.0f, FontScaleProvider.getMultiplier(-1), 0.001f)
        assertEquals(1.0f, FontScaleProvider.getMultiplier(5), 0.001f)
    }
}
