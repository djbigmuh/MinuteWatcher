package com.example.minutewatcher.feature.widget.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetSettingsState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetLayoutTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `generateLayoutSpec should produce 1 row for SINGLE_LINE mode`() {
        val uiState = createWidgetUiState(WidgetLayoutMode.SINGLE_LINE)
        val sim = uiState.simUiStates.first()

        assertEquals(1, sim.layoutSpec.rows.size)
    }

    @Test
    fun `generateLayoutSpec should produce 2 rows for TWO_LINES mode`() {
        val uiState = createWidgetUiState(WidgetLayoutMode.TWO_LINES)
        val sim = uiState.simUiStates.first()

        assertEquals(2, sim.layoutSpec.rows.size)
    }

    @Test
    fun `generateLayoutSpec should produce 3 rows for THREE_LINES mode`() {
        val uiState = createWidgetUiState(WidgetLayoutMode.THREE_LINES)
        val sim = uiState.simUiStates.first()

        assertEquals(3, sim.layoutSpec.rows.size)
    }

    @Test
    fun `generateLayoutSpec should produce 6 rows for SIX_LINES mode`() {
        val uiState = createWidgetUiState(WidgetLayoutMode.SIX_LINES)
        val sim = uiState.simUiStates.first()

        assertEquals(6, sim.layoutSpec.rows.size)
    }

    private fun createWidgetUiState(mode: WidgetLayoutMode): com.example.minutewatcher.core.model.WidgetUiState {
        val entity =
            SemanticEntity(
                id = "test",
                simSlot = 0,
                operatorName = "MTS",
                packageType = PackageType.MINUTES,
                limit = 100,
                used = 10,
                remaining = 90,
                unit = com.example.minutewatcher.core.model.MeasurementUnit.MINUTES,
                periodStart = 0,
                periodEnd = 0,
                lastUpdateTime = 0,
                sourceType = com.example.minutewatcher.core.model.DataSourceType.SYSTEM,
            )
        val simSnapshot = SimSnapshot(0, "MTS", true, mapOf(PackageType.MINUTES to entity))
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots = listOf(simSnapshot),
                capabilities = emptyList(),
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
                simStates = emptyList(),
            )
        return WidgetUiMapper.mapFromSnapshot(context, snapshot, state)
    }
}
