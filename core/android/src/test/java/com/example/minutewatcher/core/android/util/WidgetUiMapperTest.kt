package com.example.minutewatcher.core.android.util

import android.content.Context
import android.graphics.Color
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WidgetUiMapperTest {
    private val context: Context = RuntimeEnvironment.getApplication()

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
                            operator = "MTS",
                            isActive = true,
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = SemanticMapper.getDocumentId(0, PackageType.MINUTES),
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
                backgroundColor = Color.BLACK,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                callTrackingEnabled = true,
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

        val uiState = WidgetUiMapper.mapFromSnapshot(context, snapshot, settings)

        assertEquals(1, uiState.layoutMode)
        assertEquals(1, uiState.simUiStates.size)

        val sim0 = uiState.simUiStates[0]
        assertEquals(0, sim0.slot)
        assertEquals("100", sim0.minutesText)
        assertTrue(sim0.showMinutesLimit)
        assertEquals(Color.WHITE, sim0.textColor)

        // Verify full text fields
        assertTrue(sim0.minutesUsedFullText.contains("100"))
        assertTrue(sim0.minutesLimitFullText.contains("200"))
    }

    @Test
    fun testSnapshotToUiState_EmptySnapshot() {
        val snapshot = GlobalSnapshot(SchemaVersion(1, 0, 0), 0L, emptyList(), emptyList())
        val settings = WidgetSettingsState(1, 0, 50, 2, Color.BLACK, true, true, true, emptyMap())

        val uiState = WidgetUiMapper.mapFromSnapshot(context, snapshot, settings)

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
                            operator = "MTS",
                            isActive = true,
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = SemanticMapper.getDocumentId(0, PackageType.MINUTES),
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
                1,
                0,
                50,
                2,
                Color.BLACK,
                true,
                true,
                true,
                mapOf(0 to SimSettingsState(true, true, true, true, false, false, false)),
            )

        val uiState = WidgetUiMapper.mapFromSnapshot(context, snapshot, settings)
        assertEquals("150", uiState.simUiStates[0].minutesText)
    }

    @Test
    fun testSnapshotToUiState_LightBackground() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", true, emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        val settings =
            WidgetSettingsState(
                layoutMode = 1,
                simArrangement = 0,
                fontSize = 2,
                transparency = 50,
                backgroundColor = Color.WHITE,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                callTrackingEnabled = true,
                simStates = emptyMap(),
            )

        val uiState = WidgetUiMapper.mapFromSnapshot(context, snapshot, settings)
        val sim0 = uiState.simUiStates[0]

        assertEquals(Color.BLACK, sim0.textColor)
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
                            operator = "MTS",
                            isActive = true,
                            entities =
                                mapOf(
                                    PackageType.DATA to
                                        SemanticEntity(
                                            id = SemanticMapper.getDocumentId(0, PackageType.DATA),
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
                backgroundColor = Color.BLACK,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                callTrackingEnabled = true,
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

        val uiState = WidgetUiMapper.mapFromSnapshot(context, snapshot, settings)
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
                backgroundColor = Color.BLACK,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                callTrackingEnabled = true,
                simStates = emptyMap(),
            )

        val uiState = WidgetUiMapper.mapFromSnapshot(context, snapshot, settings)
        assertTrue("useSystemColors should be true for transparency > 80", uiState.useSystemColors)

        val settingsOpaque = settings.copy(transparency = 80)
        val uiStateOpaque = WidgetUiMapper.mapFromSnapshot(context, snapshot, settingsOpaque)
        assertFalse("useSystemColors should be false for transparency <= 80", uiStateOpaque.useSystemColors)
    }
}
