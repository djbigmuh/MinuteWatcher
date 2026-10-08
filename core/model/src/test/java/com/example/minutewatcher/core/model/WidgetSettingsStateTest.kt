package com.example.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        assertEquals(Constants.Widget.MAX_SELECTABLE_LAYOUT_MODE, validated.layoutMode)
        assertEquals(Constants.Widget.MIN_SIM_ARRANGEMENT, validated.simArrangement)
        assertEquals(Constants.Widget.MAX_TRANSPARENCY, validated.transparency)
        assertEquals(Constants.Widget.MAX_FONT_SIZE, validated.fontSize)
        assertEquals(simStates, validated.simStates)
        assertTrue(validated.widgetLimitExceeded)
    }

    @Test
    fun `validate clamps layoutMode 4 or above to MAX_SELECTABLE_LAYOUT_MODE`() {
        val initialMode4 = WidgetSettingsState(layoutMode = 4)
        val validatedMode4 = WidgetSettingsState.validate(initialMode4)
        assertEquals(Constants.Widget.MAX_SELECTABLE_LAYOUT_MODE, validatedMode4.layoutMode)

        for (mode in Constants.Widget.MIN_LAYOUT_MODE..Constants.Widget.MAX_SELECTABLE_LAYOUT_MODE) {
            val state = WidgetSettingsState(layoutMode = mode)
            assertEquals(mode, WidgetSettingsState.validate(state).layoutMode)
        }
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

    @Test
    fun `default aodSummaryEnabled is false`() {
        val state = WidgetSettingsState()
        assertFalse(state.aodSummaryEnabled)
    }

    @Test
    fun `validate should preserve aodSummaryEnabled`() {
        val initialEnabled = WidgetSettingsState(aodSummaryEnabled = true)
        val validatedEnabled = WidgetSettingsState.validate(initialEnabled)
        assertTrue(validatedEnabled.aodSummaryEnabled)

        val initialDisabled = WidgetSettingsState(aodSummaryEnabled = false)
        val validatedDisabled = WidgetSettingsState.validate(initialDisabled)
        assertFalse(validatedDisabled.aodSummaryEnabled)
    }

    @Test
    fun `default callTrackingEnabled is true`() {
        val state = WidgetSettingsState()
        assertTrue(state.callTrackingEnabled)
    }

    @Test
    fun `default assistantAccessEnabled is false`() {
        val state = WidgetSettingsState()
        assertFalse(state.assistantAccessEnabled)
    }

    @Test
    fun `validate should preserve callTrackingEnabled`() {
        val initialEnabled = WidgetSettingsState(callTrackingEnabled = true)
        val validatedEnabled = WidgetSettingsState.validate(initialEnabled)
        assertTrue(validatedEnabled.callTrackingEnabled)

        val initialDisabled = WidgetSettingsState(callTrackingEnabled = false)
        val validatedDisabled = WidgetSettingsState.validate(initialDisabled)
        assertFalse(validatedDisabled.callTrackingEnabled)
    }

    @Test
    fun `validate should preserve assistantAccessEnabled`() {
        val initialEnabled = WidgetSettingsState(assistantAccessEnabled = true)
        val validatedEnabled = WidgetSettingsState.validate(initialEnabled)
        assertTrue(validatedEnabled.assistantAccessEnabled)

        val initialDisabled = WidgetSettingsState(assistantAccessEnabled = false)
        val validatedDisabled = WidgetSettingsState.validate(initialDisabled)
        assertFalse(validatedDisabled.assistantAccessEnabled)
    }
}
