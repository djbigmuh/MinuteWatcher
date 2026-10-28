package host.serv.djbigmuh.minutewatcher.core.android.util

import host.serv.djbigmuh.minutewatcher.core.android.testing.SingletonResetRule
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.WidgetLayoutMode
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetUiLayoutLimitMapperTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    @Test
    fun `widget limit exceeded overrides layout to simple`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                layoutMode = 3, // SIX_LINES
                widgetLimitExceeded = true,
                simStates = emptyMap(),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )
        assertEquals(WidgetLayoutMode.SIMPLE, WidgetLayoutMode.fromValue(uiState.layoutMode))
    }

    @Test
    fun `preemptive layout limit triggered when visible sims count reaches limit`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", emptyMap(), 1),
                        SimSnapshot(1, "sim_1", "Operator", emptyMap(), 1),
                        SimSnapshot(2, "sim_2", "Operator", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                layoutMode = 3, // SIX_LINES
                simStates =
                    mapOf(
                        0 to SimSettingsState(showSim = true),
                        1 to SimSettingsState(showSim = true),
                        2 to SimSettingsState(showSim = true),
                    ),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = emptyMap(),
            )
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(uiState.layoutMode))
    }

    @Test
    fun `preemptive layout limit not triggered when visible sims count is below limit`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", emptyMap(), 1),
                        SimSnapshot(1, "sim_1", "Operator", emptyMap(), 1),
                        SimSnapshot(2, "sim_2", "Operator", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                layoutMode = 3, // SIX_LINES
                simStates =
                    mapOf(
                        0 to SimSettingsState(showSim = true),
                        1 to SimSettingsState(showSim = false),
                        2 to SimSettingsState(showSim = false),
                    ),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )
        assertEquals(WidgetLayoutMode.SIX_LINES, WidgetLayoutMode.fromValue(uiState.layoutMode))
    }

    @Test
    fun `widget limit exceeded has priority over preemptive limit`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", emptyMap(), 1),
                        SimSnapshot(1, "sim_1", "Operator", emptyMap(), 1),
                        SimSnapshot(2, "sim_2", "Operator", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                layoutMode = 3, // SIX_LINES
                widgetLimitExceeded = true,
                simStates =
                    mapOf(
                        0 to SimSettingsState(showSim = true),
                        1 to SimSettingsState(showSim = true),
                        2 to SimSettingsState(showSim = true),
                    ),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )
        assertEquals(WidgetLayoutMode.SIMPLE, WidgetLayoutMode.fromValue(uiState.layoutMode))
    }

    @Test
    fun `missing simStates entry defaults showSim to loadFailed and keeps six lines when load succeeded`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", emptyMap(), 1),
                        SimSnapshot(1, "sim_1", "Operator", emptyMap(), 1),
                        SimSnapshot(2, "sim_2", "Operator", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                layoutMode = 3, // SIX_LINES
                simStates = emptyMap(),
                simListLoadFailed = false,
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = emptyMap(),
            )
        assertEquals(WidgetLayoutMode.SIX_LINES, WidgetLayoutMode.fromValue(uiState.layoutMode))
    }
}
