package com.example.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WidgetSettingsStateTest {
    @Test
    fun `validate should clamp values and preserve simStates`() {
        val simStates =
            mapOf(
                0 to SimSettingsState(showSim = false),
                1 to SimSettingsState(showTraffic = true),
            )
        val initial =
            WidgetSettingsState(
                layoutMode = 99, // invalid
                simArrangement = -1, // invalid
                transparency = 150, // invalid
                fontSize = 10, // invalid
                simStates = simStates,
                widgetLimitExceeded = true,
            )

        val validated = WidgetSettingsState.validate(initial)

        assertEquals(Constants.Widget.MAX_LAYOUT_MODE, validated.layoutMode)
        assertEquals(Constants.Widget.MIN_SIM_ARRANGEMENT, validated.simArrangement)
        assertEquals(Constants.Widget.MAX_TRANSPARENCY, validated.transparency)
        assertEquals(Constants.Widget.MAX_FONT_SIZE, validated.fontSize)
        assertEquals(simStates, validated.simStates)
        assertFalse(validated.widgetLimitExceeded)
    }

    @Test
    fun `validate should preserve notification settings`() {
        val initial =
            WidgetSettingsState(
                notifyLowMinutes = false,
                notifyLowTraffic = false,
            )

        val validated = WidgetSettingsState.validate(initial)

        assertFalse(validated.notifyLowMinutes)
        assertFalse(validated.notifyLowTraffic)
    }
}
