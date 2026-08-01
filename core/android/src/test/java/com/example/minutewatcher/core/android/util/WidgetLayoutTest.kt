package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetLayoutTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `generateLayoutSpec should produce 1 row for SINGLE_LINE mode`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.SINGLE_LINE,
                hasMinutes = true,
                hasTraffic = true,
            )
        val sim = uiState.simUiStates.first()
        // Icon + Name + Minutes + Traffic = 1 row
        assertEquals(1, sim.layoutSpec.rows.size)
    }

    @Test
    fun `generateLayoutSpec should produce 2 rows for TWO_LINES mode`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.TWO_LINES,
                hasMinutes = true,
                hasTraffic = true,
            )
        val sim = uiState.simUiStates.first()
        // Row 1: Icon + Name
        // Row 2: Minutes + Traffic
        assertEquals(2, sim.layoutSpec.rows.size)
    }

    @Test
    fun `generateLayoutSpec should produce 3 rows for THREE_LINES mode`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.THREE_LINES,
                hasMinutes = true,
                hasTraffic = true,
                showMinutesLimit = true,
                showTrafficLimit = true,
            )
        val sim = uiState.simUiStates.first()
        // Row 1: Icon + Name
        // Row 2: Minutes (with limit)
        // Row 3: Traffic (with limit)
        assertEquals(3, sim.layoutSpec.rows.size)
    }

    @Test
    fun `generateLayoutSpec should produce 6 rows for SIX_LINES mode when all data and limits are present`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.SIX_LINES,
                hasMinutes = true,
                hasTraffic = true,
                showMinutesLimit = true,
                showTrafficLimit = true,
            )
        val sim = uiState.simUiStates.first()
        // 1: Name
        // 2: Icon
        // 3: Minutes Used
        // 4: Minutes Limit
        // 5: Traffic Used
        // 6: Traffic Limit
        assertEquals(6, sim.layoutSpec.rows.size)
    }

    @Test
    fun `SIX_LINES mode should hide missing traffic section`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.SIX_LINES,
                hasMinutes = true,
                hasTraffic = false,
                showMinutesLimit = true,
            )
        val sim = uiState.simUiStates.first()
        // 1: Name
        // 2: Icon
        // 3: Minutes Used
        // 4: Minutes Limit
        // (Traffic rows hidden)
        assertEquals(4, sim.layoutSpec.rows.size)
    }

    @Test
    fun `SIX_LINES mode should hide limits when disabled in settings`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.SIX_LINES,
                hasMinutes = true,
                hasTraffic = true,
                showMinutesLimit = false,
                showTrafficLimit = false,
            )
        val sim = uiState.simUiStates.first()
        // 1: Name
        // 2: Icon
        // 3: Minutes Used
        // 4: Traffic Used
        assertEquals(4, sim.layoutSpec.rows.size)
    }

    @Test
    fun `SIX_LINES mode should show only name and icon when no data is present`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.SIX_LINES,
                hasMinutes = false,
                hasTraffic = false,
            )
        val sim = uiState.simUiStates.first()
        // 1: Name
        // 2: Icon
        assertEquals(2, sim.layoutSpec.rows.size)
    }

    private fun createWidgetUiState(
        mode: WidgetLayoutMode,
        hasMinutes: Boolean = true,
        hasTraffic: Boolean = true,
        showMinutesLimit: Boolean = false,
        showTrafficLimit: Boolean = false,
    ): WidgetUiState {
        val entities = mutableMapOf<PackageType, SemanticEntity>()
        if (hasMinutes) {
            entities[PackageType.MINUTES] =
                SemanticEntity(
                    id = "minutes",
                    simSlot = 0,
                    operatorName = "MTS",
                    packageType = PackageType.MINUTES,
                    limit = 100,
                    used = 10,
                    remaining = 90,
                    unit = MeasurementUnit.MINUTES,
                    periodStart = 0,
                    periodEnd = 0,
                    lastUpdateTime = 0,
                    sourceType = DataSourceType.SYSTEM,
                )
        }
        if (hasTraffic) {
            entities[PackageType.DATA] =
                SemanticEntity(
                    id = "traffic",
                    simSlot = 0,
                    operatorName = "MTS",
                    packageType = PackageType.DATA,
                    limit = 1024L * 1024 * 1024 * 2, // 2 GB
                    used = 1024L * 1024 * 512, // 512 MB
                    remaining = 1024L * 1024 * 1024 * 1 + 1024L * 1024 * 512, // 1.5 GB
                    unit = MeasurementUnit.BYTES,
                    periodStart = 0,
                    periodEnd = 0,
                    lastUpdateTime = 0,
                    sourceType = DataSourceType.SYSTEM,
                )
        }

        val simSnapshot = SimSnapshot(0, "MTS", "MTS", true, entities)
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots = listOf(simSnapshot),
                capabilities = emptyList(),
            )

        val simSettings =
            SimSettingsState(
                showSim = true,
                showIcon = true,
                showName = true,
                showMinutes = true,
                showMinutesLimit = showMinutesLimit,
                showTraffic = true,
                showTrafficLimit = showTrafficLimit,
            )

        val state =
            WidgetSettingsState(
                layoutMode = mode.value,
                simArrangement = 0,
                transparency = 0,
                fontSize = 2,
                backgroundColor = 0,
                notifyLowMinutes = true,
                notifyLowTraffic = true,
                callTrackingEnabled = true,
                simStates = mapOf(0 to simSettings),
            )
        return WidgetUiMapper.mapFromSnapshot(context, snapshot, state)
    }
}
