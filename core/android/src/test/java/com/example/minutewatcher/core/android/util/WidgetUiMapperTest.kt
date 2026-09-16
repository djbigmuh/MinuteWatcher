package com.example.minutewatcher.core.android.util

import android.graphics.Color
import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetUiMapperTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    private val COLOR_BLACK = Color.BLACK
    private val COLOR_WHITE = Color.WHITE

    private val widgetStrings =
        WidgetStrings(
            usedText = "Used",
            minutesAbbr = "min.",
            limitText = "Limit",
            gbAbbr = "GB",
            fractionFormat = "/%1\$s",
        )

    @Test
    fun testSnapshotToUiState_Basic() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "sim_0",
                            operatorName = "MTS",
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "sim_0_minutes",
                                            simSlot = 0,
                                            operatorName = "MTS",
                                            packageType = PackageType.MINUTES,
                                            limit = 200,
                                            used = 100,
                                            remaining = 100,
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
                layoutMode = 1,
                simArrangement = 0,
                fontSize = 2,
                transparency = 50,
                backgroundColor = COLOR_BLACK,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                simStates =
                    mapOf(
                        0 to
                            SimSettingsState(
                                showSim = true,
                                showIcon = true,
                                showName = true,
                                showMinutes = true,
                                showMinutesLimit = true,
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

        assertEquals(1, uiState.layoutMode)
        assertEquals(1, uiState.simUiStates.size)

        val sim0 = uiState.simUiStates[0]
        assertEquals(0, sim0.slot)
        assertEquals("100", sim0.minutesText)
        assertEquals("min.", sim0.minutesSuffix)
        assertEquals("/200", sim0.minutesLimitText)
        assertTrue(sim0.showMinutesLimit)
        assertEquals(COLOR_WHITE, sim0.textColor)

        // Verify limit is in layout spec for TWO_LINES mode
        val limitComponent =
            sim0.layoutSpec.rows
                .flatMap { it.components }
                .find { it is SimLayoutComponent.Text && it.text == "/200" }
        assertTrue("Limit should be present in layout spec", limitComponent != null)

        // Verify full text fields
        assertTrue(sim0.minutesUsedFullText.contains("100"))
        assertTrue(sim0.minutesLimitFullText.contains("200"))
    }

    @Test
    fun testSnapshotToUiState_EmptySnapshot() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
        val settings = WidgetSettingsState(1, 0, 50, 2, COLOR_BLACK, true, true, emptyMap())

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
    fun testSnapshotToUiState_ManualAdjustment() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "sim_0",
                            operatorName = "MTS",
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "sim_0_minutes",
                                            simSlot = 0,
                                            operatorName = "MTS",
                                            packageType = PackageType.MINUTES,
                                            limit = 200,
                                            used = 100,
                                            remaining = 150, // 200 - 100 + 50 adj
                                            unit = MeasurementUnit.MINUTES,
                                            periodStart = 0,
                                            periodEnd = 0,
                                            lastUpdateTime = 0,
                                            sourceType = DataSourceType.MANUAL,
                                        ),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                capabilities = emptyList(),
            )
        val settings =
            WidgetSettingsState(
                layoutMode = 1,
                simArrangement = 0,
                transparency = 50,
                fontSize = 2,
                backgroundColor = COLOR_BLACK,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                simStates = mapOf(0 to SimSettingsState(true, true, true, true, false, false, false)),
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
    fun testSnapshotToUiState_LightBackground() {
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
                backgroundColor = COLOR_WHITE,
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

        assertEquals(COLOR_BLACK, sim0.textColor)
    }

    @Test
    fun testSnapshotToUiState_HidesEmptyTraffic() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(
                            slot = 0,
                            identity = "sim_0",
                            operatorName = "MTS",
                            entities =
                                mapOf(
                                    PackageType.DATA to
                                        SemanticEntity(
                                            id = "sim_0_data",
                                            simSlot = 0,
                                            operatorName = "MTS",
                                            packageType = PackageType.DATA,
                                            limit = 0, // No limit
                                            used = 0, // No usage
                                            remaining = 0,
                                            unit = MeasurementUnit.BYTES,
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
                layoutMode = 3, // SIX_LINES
                simArrangement = 0,
                fontSize = 2,
                transparency = 50,
                backgroundColor = COLOR_BLACK,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                simStates =
                    mapOf(
                        0 to
                            SimSettingsState(
                                showSim = true,
                                showIcon = true,
                                showName = true,
                                showMinutes = true,
                                showMinutesLimit = true,
                                showTraffic = true, // Enabled in settings but data is empty
                                showTrafficLimit = true,
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
        val sim0 = uiState.simUiStates[0]

        // Should be hidden because limit and used are 0
        assertFalse(sim0.showTraffic)

        // Verify layout spec does not contain traffic rows
        val trafficRows =
            sim0.layoutSpec.rows.filter { row ->
                row.components.any { component ->
                    component is SimLayoutComponent.Text &&
                        component.text.contains("Used:") &&
                        component.text.contains("GB")
                }
            }
        assertTrue(trafficRows.isEmpty())
    }

    @Test
    fun testTransparencyMapping() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), System.currentTimeMillis(), emptyList(), emptyList())
        val settings =
            WidgetSettingsState(
                layoutMode = 1,
                simArrangement = 0,
                fontSize = 2,
                transparency = 81,
                backgroundColor = COLOR_BLACK,
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
    fun testWidgetLimitExceeded_OverridesLayoutToSimple() {
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
    fun testShowSimLogic_WhenConfigMissing() {
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
}
