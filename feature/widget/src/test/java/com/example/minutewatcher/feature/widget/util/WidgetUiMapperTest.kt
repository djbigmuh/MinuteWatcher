package com.example.minutewatcher.feature.widget.util

import android.content.Context
import android.graphics.Color
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
                            operator = "MTS",
                            isActive = true,
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "sim_0_MINUTES",
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
                    listOf(
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
        val settings = WidgetSettingsState(1, 0, 50, 2, Color.BLACK, true, true, true, emptyList())

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
                            operator = "MTS",
                            isActive = true,
                            entities =
                                mapOf(
                                    PackageType.MINUTES to
                                        SemanticEntity(
                                            id = "sim_0_MINUTES",
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
                listOf(SimSettingsState(true, true, true, true, false, false, false)),
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
                        SimSnapshot(0, "Operator", true, emptyMap()),
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
                simStates = emptyList(),
            )

        val uiState = WidgetUiMapper.mapFromSnapshot(context, snapshot, settings)
        val sim0 = uiState.simUiStates[0]

        assertEquals(Color.BLACK, sim0.textColor)
    }
}
