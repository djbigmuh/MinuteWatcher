package host.serv.djbigmuh.minutewatcher.core.android.util

import android.graphics.Color
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.SimLayoutComponent
import host.serv.djbigmuh.minutewatcher.core.model.SimSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.WidgetSettingsState
import host.serv.djbigmuh.minutewatcher.core.model.WidgetStrings
import host.serv.djbigmuh.minutewatcher.core.model.WidgetUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

val colorBlack: Int = Color.BLACK
val colorWhite: Int = Color.WHITE

val widgetStrings =
    WidgetStrings(
        usedText = "Used",
        minutesAbbr = "min.",
        limitText = "Limit",
        gbAbbr = "GB",
    )

fun buildBasicSnapshot() =
    GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 1_000_000L,
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

fun buildBasicSettings() =
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

fun verifyBasicUiState(uiState: WidgetUiState) {
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

fun buildManualAdjustmentSnapshot() =
    GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 1_000_000L,
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

fun buildEmptyTrafficSnapshot() =
    GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 1_000_000L,
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

fun buildSixLinesSettings() =
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
