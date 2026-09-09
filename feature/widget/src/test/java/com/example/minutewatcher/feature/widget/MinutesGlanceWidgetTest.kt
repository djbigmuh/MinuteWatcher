package com.example.minutewatcher.feature.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinutesGlanceWidgetTest {
    @Test
    fun `constructor sets forceFullRender property correctly`() {
        val widget1 = MinutesGlanceWidget(forceFullRender = true)
        val widget2 = MinutesGlanceWidget(forceFullRender = false)
        val widgetDefault = MinutesGlanceWidget()

        assertTrue(widget1.forceFullRender)
        assertFalse(widget2.forceFullRender)
        assertFalse(widgetDefault.forceFullRender)
    }

    @Test
    fun `concurrent instances maintain their own state`() {
        // Since we moved from static to instance property, each instance is independent.
        val widget1 = MinutesGlanceWidget(forceFullRender = true)
        val widget2 = MinutesGlanceWidget(forceFullRender = false)

        assertTrue("Instance 1 should have forceFullRender=true", widget1.forceFullRender)
        assertFalse("Instance 2 should have forceFullRender=false", widget2.forceFullRender)

        // Changing or creating another doesn't affect existing ones
        val widget3 = MinutesGlanceWidget(forceFullRender = true)
        assertFalse("Existing Instance 2 should still have forceFullRender=false", widget2.forceFullRender)
        assertTrue("Instance 3 should have forceFullRender=true", widget3.forceFullRender)
    }
}
