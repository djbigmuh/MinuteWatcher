package com.github.djbigmuh.minutewatcher.core.android.util

import com.github.djbigmuh.minutewatcher.core.android.testing.SingletonResetRule
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.SimSettingsState
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import com.github.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import com.github.djbigmuh.minutewatcher.core.model.resolveShowSim
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetUiSimVisibilityMapperTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    @Test
    fun `show SIM logic when config missing`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 1_000_000L,
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        // Scenario 1: Config missing, load succeeded -> showSim should be false
        val settingsNormal = WidgetSettingsState(simStates = emptyMap(), simListLoadFailed = false)
        val uiStateNormal =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settingsNormal,
                operatorNames = emptyMap(),
            )
        assertFalse(
            "showSim should be false when config missing and load succeeded",
            uiStateNormal.simUiStates[0].showSim,
        )

        // Scenario 2: Config missing, load failed -> showSim should be true (fallback)
        val settingsFailed = WidgetSettingsState(simStates = emptyMap(), simListLoadFailed = true)
        val uiStateFailed =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settingsFailed,
                operatorNames = emptyMap(),
            )
        assertTrue("showSim should be true when config missing and load failed", uiStateFailed.simUiStates[0].showSim)
    }

    @Test
    fun `consistency test between visibility predicate and render filter count`() {
        val simStates =
            mapOf(
                0 to
                    SimSettingsState(showSim = true),
                1 to
                    SimSettingsState(showSim = false),
                2 to
                    SimSettingsState(showSim = true),
            )
        val state = WidgetSettingsState(simStates = simStates, simListLoadFailed = false)
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 1000L,
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "id0", "Op0", emptyMap(), 1),
                        SimSnapshot(1, "id1", "Op1", emptyMap(), 1),
                        SimSnapshot(2, "id2", "Op2", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        val predicateCount = state.simStates.values.count { it.resolveShowSim(state.simListLoadFailed) }
        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = state,
                operatorNames = emptyMap(),
            )
        val renderFilterCount = uiState.simUiStates.count { it.showSim }

        org.junit.Assert.assertEquals(predicateCount, renderFilterCount)
        org.junit.Assert.assertEquals(2, predicateCount)
    }
}
