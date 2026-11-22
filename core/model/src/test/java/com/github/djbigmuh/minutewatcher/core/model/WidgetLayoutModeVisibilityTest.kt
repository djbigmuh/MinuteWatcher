package com.github.djbigmuh.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutModeVisibilityTest {
    @Test
    fun `visibleSimCount and showLayoutMode correctly reflect 0, 1, 2, and 3 visible SIMs`() {
        val stateZero = WidgetSettingsState(simStates = emptyMap(), simListLoadFailed = false)
        assertEquals(0, stateZero.visibleSimCount)
        assertFalse(stateZero.showLayoutMode)

        val stateOne =
            WidgetSettingsState(
                simStates = mapOf(0 to SimSettingsState(showSim = true)),
                simListLoadFailed = false,
            )
        assertEquals(1, stateOne.visibleSimCount)
        assertFalse(stateOne.showLayoutMode)

        val stateTwo =
            WidgetSettingsState(
                simStates =
                    mapOf(
                        0 to SimSettingsState(showSim = true),
                        1 to SimSettingsState(showSim = true),
                    ),
                simListLoadFailed = false,
            )
        assertEquals(2, stateTwo.visibleSimCount)
        assertTrue(stateTwo.showLayoutMode)

        val stateThree =
            WidgetSettingsState(
                simStates =
                    mapOf(
                        0 to SimSettingsState(showSim = true),
                        1 to SimSettingsState(showSim = true),
                        2 to SimSettingsState(showSim = true),
                    ),
                simListLoadFailed = false,
            )
        assertEquals(3, stateThree.visibleSimCount)
        assertTrue(stateThree.showLayoutMode)
    }

    @Test
    fun `toggling showSim flag updates visibleSimCount and showLayoutMode`() {
        var state =
            WidgetSettingsState(
                simStates =
                    mapOf(
                        0 to SimSettingsState(showSim = true),
                        1 to SimSettingsState(showSim = true),
                    ),
            )
        assertEquals(2, state.visibleSimCount)
        assertTrue(state.showLayoutMode)

        // Hide slot 1
        val updatedSimStates = state.simStates.toMutableMap()
        updatedSimStates[1] = updatedSimStates[1]!!.copy(showSim = false)
        state = state.copy(simStates = updatedSimStates)

        assertEquals(1, state.visibleSimCount)
        assertFalse(state.showLayoutMode)

        // Show slot 1 again
        updatedSimStates[1] = updatedSimStates[1]!!.copy(showSim = true)
        state = state.copy(simStates = updatedSimStates)

        assertEquals(2, state.visibleSimCount)
        assertTrue(state.showLayoutMode)
    }

    @Test
    fun `changing active SIM set updates visibleSimCount`() {
        var state =
            WidgetSettingsState(
                simStates = mapOf(0 to SimSettingsState(showSim = true)),
            )
        assertEquals(1, state.visibleSimCount)
        assertFalse(state.showLayoutMode)

        // Add active SIM at slot 1
        state =
            state.copy(
                simStates =
                    mapOf(
                        0 to SimSettingsState(showSim = true),
                        1 to SimSettingsState(showSim = true),
                    ),
            )
        assertEquals(2, state.visibleSimCount)
        assertTrue(state.showLayoutMode)
    }

    @Test
    fun `reloading state with load failure fallback handles visibility correctly`() {
        val stateFailed =
            WidgetSettingsState(
                simStates = emptyMap(),
                simListLoadFailed = true,
            )
        assertEquals(0, stateFailed.visibleSimCount)
        assertFalse(stateFailed.showLayoutMode)
    }
}
