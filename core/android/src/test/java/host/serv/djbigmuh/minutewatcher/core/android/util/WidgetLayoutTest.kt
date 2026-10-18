package host.serv.djbigmuh.minutewatcher.core.android.util

import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimLayoutComponent
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.WidgetLayoutMode
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetStrings
import host.serv.djbigmuh.minutewatcher.core.model.WidgetUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutTest {
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
    fun `generateLayoutSpec should produce 1 row for SIMPLE mode`() {
        val uiState =
            createWidgetUiState(
                mode = WidgetLayoutMode.SIMPLE,
                hasMinutes = true,
                hasTraffic = true,
            )
        val sim = uiState.simUiStates.first()
        // Usage info only = 1 row
        assertEquals(1, sim.layoutSpec.rows.size)
    }

    @Test
    fun `WidgetLayoutMode fromValue should fallback to THREE_LINES for invalid values`() {
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(-1))
        assertEquals(WidgetLayoutMode.THREE_LINES, WidgetLayoutMode.fromValue(999))
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

    @Test
    fun `Usage components count should be correct with and without limit`() {
        // Without limit: Value + Suffix = 2 text components
        val uiStateNoLimit =
            createWidgetUiState(
                mode = WidgetLayoutMode.SIMPLE,
                hasMinutes = true,
                hasTraffic = false,
                showMinutesLimit = false,
            )
        val simNoLimit = uiStateNoLimit.simUiStates.first()
        val textComponentsNoLimit =
            simNoLimit.layoutSpec.rows[0]
                .components
                .filterIsInstance<SimLayoutComponent.Text>()
        assertEquals(2, textComponentsNoLimit.size)
        assertEquals("90", textComponentsNoLimit[0].text)
        assertEquals("min.", textComponentsNoLimit[1].text)

        // With limit: Value + Limit + Suffix = 3 text components
        val uiStateWithLimit =
            createWidgetUiState(
                mode = WidgetLayoutMode.SIMPLE,
                hasMinutes = true,
                hasTraffic = false,
                showMinutesLimit = true,
            )
        val simWithLimit = uiStateWithLimit.simUiStates.first()
        val textComponentsWithLimit =
            simWithLimit.layoutSpec.rows[0]
                .components
                .filterIsInstance<SimLayoutComponent.Text>()
        assertEquals(3, textComponentsWithLimit.size)
        assertEquals("90", textComponentsWithLimit[0].text)
        assertEquals("/100", textComponentsWithLimit[1].text)
        assertEquals("min.", textComponentsWithLimit[2].text)
    }

    private fun createWidgetUiState(
        mode: WidgetLayoutMode,
        hasMinutes: Boolean = true,
        hasTraffic: Boolean = true,
        showMinutesLimit: Boolean = false,
        showTrafficLimit: Boolean = false,
    ): WidgetUiState {
        val entities = buildEntities(hasMinutes, hasTraffic)
        val snapshot = buildSnapshot(entities)
        val state = buildSettings(mode, showMinutesLimit, showTrafficLimit)
        val widgetStrings = buildStrings()

        return WidgetUiMapper.mapFromSnapshot(
            strings = widgetStrings,
            snapshot = snapshot,
            state = state,
            operatorNames = mapOf(0 to "MTS"),
        )
    }

    private fun buildEntities(
        hasMinutes: Boolean,
        hasTraffic: Boolean,
    ): MutableMap<PackageType, SemanticEntity> {
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
                    limit = 1024L * 1024 * 1024 * 2,
                    used = 1024L * 1024 * 512,
                    remaining = 1024L * 1024 * 1024 * 1 + 1024L * 1024 * 512,
                    unit = MeasurementUnit.BYTES,
                    periodStart = 0,
                    periodEnd = 0,
                    lastUpdateTime = 0,
                    sourceType = DataSourceType.SYSTEM,
                )
        }
        return entities
    }

    private fun buildSnapshot(entities: Map<PackageType, SemanticEntity>) =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = System.currentTimeMillis(),
            simSnapshots = listOf(SimSnapshot(0, "MTS", "MTS", entities, autoResetDay = 1)),
            capabilities = emptyList(),
        )

    private fun buildSettings(
        mode: WidgetLayoutMode,
        showMinutesLimit: Boolean,
        showTrafficLimit: Boolean,
    ): WidgetSettingsState {
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
        return WidgetSettingsState(
            layoutMode = mode.value,
            simArrangement = 0,
            transparency = 0,
            fontSize = 2,
            backgroundColor = 0,
            notifyLowMinutes = true,
            notifyLowTraffic = true,
            simStates = mapOf(0 to simSettings),
        )
    }

    private fun buildStrings() =
        WidgetStrings(
            usedText = "Used",
            minutesAbbr = "min.",
            limitText = "Limit",
            gbAbbr = "GB",
        )

    @Test
    fun `SIMPLE mode does not contain icon components`() {
        val uiState = createWidgetUiState(mode = WidgetLayoutMode.SIMPLE)
        val sim = uiState.simUiStates.first()
        val icons =
            sim.layoutSpec.rows
                .flatMap { row -> row.components }
                .filterIsInstance<SimLayoutComponent.Icon>()
        assertEquals(0, icons.size)
    }
}
