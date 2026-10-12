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
import com.example.minutewatcher.core.model.WidgetUiState
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

    private val colorBlack = Color.BLACK
    private val colorWhite = Color.WHITE

    private val widgetStrings =
        WidgetStrings(
            usedText = "Used",
            minutesAbbr = "min.",
            limitText = "Limit",
            gbAbbr = "GB",
        )

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

    private fun buildBasicSnapshot() =
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

    private fun buildBasicSettings() =
        WidgetSettingsState(
            layoutMode = 1,
            simArrangement = 0,
            fontSize = 2,
            transparency = 50,
            backgroundColor = colorBlack,
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

    private fun verifyBasicUiState(uiState: WidgetUiState) {
        assertEquals(1, uiState.layoutMode)
        assertEquals(1, uiState.simUiStates.size)

        val sim0 = uiState.simUiStates[0]
        assertEquals(0, sim0.slot)
        assertEquals("100", sim0.minutesText)
        assertEquals("min.", sim0.minutesSuffix)
        assertEquals("/200", sim0.minutesLimitText)
        assertTrue(sim0.showMinutesLimit)
        assertEquals(colorWhite, sim0.textColor)

        val limitComponent =
            sim0.layoutSpec.rows
                .flatMap { it.components }
                .find { it is SimLayoutComponent.Text && it.text == "/200" }
        assertTrue("Limit should be present in layout spec", limitComponent != null)

        assertTrue(sim0.minutesUsedFullText.contains("100"))
        assertTrue(sim0.minutesLimitFullText.contains("200"))
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

    private fun buildManualAdjustmentSnapshot() =
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
                    component is SimLayoutComponent.Text &&
                        component.text.contains("Used:") &&
                        component.text.contains("GB")
                }
            }
        assertTrue(trafficRows.isEmpty())
    }

    private fun buildEmptyTrafficSnapshot() =
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
                                        limit = 0,
                                        used = 0,
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

    private fun buildSixLinesSettings() =
        WidgetSettingsState(
            layoutMode = 3, // SIX_LINES
            simArrangement = 0,
            fontSize = 2,
            transparency = 50,
            backgroundColor = colorBlack,
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
                            showTraffic = true,
                            showTrafficLimit = true,
                        ),
                ),
        )

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
    fun `show SIM logic when config missing`() {
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
