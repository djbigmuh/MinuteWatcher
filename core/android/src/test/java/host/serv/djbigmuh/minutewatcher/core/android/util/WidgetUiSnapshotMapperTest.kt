package host.serv.djbigmuh.minutewatcher.core.android.util

import host.serv.djbigmuh.minutewatcher.core.android.testing.SingletonResetRule
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetUiSnapshotMapperTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    @Test
    fun `snapshot to ui state basic`() {
        val snapshot = buildBasicSnapshot()
        val settings = buildBasicSettings()

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )

        verifyBasicUiState(uiState)
    }

    @Test
    fun `snapshot to ui state empty snapshot`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = 0L,
                simSnapshots = emptyList(),
                capabilities = emptyList(),
            )
        val settings =
            WidgetSettingsState(
                layoutMode = 1,
                simArrangement = 0,
                transparency = 50,
                fontSize = 2,
                backgroundColor = colorBlack,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                simStates = emptyMap(),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )

        assertTrue(uiState.simUiStates.isEmpty())
    }

    @Test
    fun `snapshot to ui state manual adjustment`() {
        val snapshot = buildManualAdjustmentSnapshot()
        val settings =
            buildBasicSettings().copy(
                simStates =
                    mapOf(
                        0 to
                            SimSettingsState(
                                showSim = true,
                                showIcon = true,
                                showName = true,
                                showMinutes = true,
                                showMinutesLimit = false,
                                showTraffic = false,
                                showTrafficLimit = false,
                            ),
                    ),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )
        assertEquals("150", uiState.simUiStates[0].minutesText)
        assertEquals("min.", uiState.simUiStates[0].minutesSuffix)
    }

    @Test
    fun `snapshot to ui state light background`() {
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
                layoutMode = 1,
                simArrangement = 0,
                fontSize = 2,
                transparency = 50,
                backgroundColor = colorWhite,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                simStates = emptyMap(),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )
        val sim0 = uiState.simUiStates[0]

        assertEquals(colorBlack, sim0.textColor)
    }

    @Test
    fun `snapshot to ui state hides empty traffic`() {
        val snapshot = buildEmptyTrafficSnapshot()
        val settings = buildSixLinesSettings()

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )
        val sim0 = uiState.simUiStates[0]

        // Should be hidden because limit and used are 0
        assertFalse(sim0.showTraffic)

        // Verify layout spec does not contain traffic rows
        val trafficRows =
            sim0.layoutSpec.rows.filter { row ->
                row.components.any { component ->
                    component is host.serv.djbigmuh.minutewatcher.core.model.SimLayoutComponent.Text &&
                        component.text.contains("Used:") &&
                        component.text.contains("GB")
                }
            }
        assertTrue(trafficRows.isEmpty())
    }

    @Test
    fun `transparency mapping`() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList())
        val settings =
            WidgetSettingsState(
                layoutMode = 1,
                simArrangement = 0,
                fontSize = 2,
                transparency = 81,
                backgroundColor = colorBlack,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                simStates = emptyMap(),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )
        assertTrue("useSystemColors should be true for transparency > 80", uiState.useSystemColors)

        val settingsOpaque = settings.copy(transparency = 80)
        val uiStateOpaque =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settingsOpaque,
                operatorNames = emptyMap(),
            )
        assertFalse("useSystemColors should be false for transparency <= 80", uiStateOpaque.useSystemColors)
    }

    @Test
    fun `minutes text displays dash when limit is zero and used is non zero`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "sim_0",
                            operatorName = "Operator",
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "entity_0",
                                            simSlot = 0,
                                            operatorName = "Operator",
                                            packageType = PackageType.MINUTES,
                                            limit = 0L,
                                            used = 120L,
                                            remaining = 0L,
                                            unit = MeasurementUnit.MINUTES,
                                            periodStart = 0,
                                            periodEnd = 0,
                                            lastUpdateTime = 0,
                                            sourceType = DataSourceType.SYSTEM,
                                        ),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                simStates =
                    mapOf(
                        0 to SimSettingsState(showMinutes = true),
                    ),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )

        val simUiState = uiState.simUiStates[0]
        assertTrue("showMinutes should be true when used > 0", simUiState.showMinutes)
        assertEquals("—", simUiState.minutesText)
    }

    @Test
    fun `minutes text displays remaining value when limit is set`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "sim_0",
                            operatorName = "Operator",
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "entity_0",
                                            simSlot = 0,
                                            operatorName = "Operator",
                                            packageType = PackageType.MINUTES,
                                            limit = 500L,
                                            used = 120L,
                                            remaining = 380L,
                                            unit = MeasurementUnit.MINUTES,
                                            periodStart = 0,
                                            periodEnd = 0,
                                            lastUpdateTime = 0,
                                            sourceType = DataSourceType.SYSTEM,
                                        ),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                simStates =
                    mapOf(
                        0 to SimSettingsState(showMinutes = true),
                    ),
            )

        val uiState =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settings,
                operatorNames = mapOf(0 to "MTS"),
            )

        val simUiState = uiState.simUiStates[0]
        assertTrue("showMinutes should be true", simUiState.showMinutes)
        assertEquals("380", simUiState.minutesText)
    }
}
