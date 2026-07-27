package com.example.minutewatcher.feature.widget.util

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutRow
import com.example.minutewatcher.core.model.SimLayoutSpec
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.model.util.ColorProvider
import com.example.minutewatcher.core.model.util.FontScaleProvider
import com.example.minutewatcher.core.model.util.FormatUtils
import com.example.minutewatcher.core.model.util.SimUtils

object WidgetUiMapper {
    private const val SIZE_ICON_SINGLE = 14
    private const val SIZE_ICON_MULTI = 16
    private const val FONT_SINGLE_NAME = 10f
    private const val FONT_SINGLE_VALUE = 12f
    private const val FONT_MULTI_NAME = 12f
    private const val FONT_MULTI_VALUE = 14f
    private const val FONT_LIMIT = 10f
    private const val FONT_FULL = 11f
    private const val SPACING_DEFAULT = 4
    private const val SPACING_SMALL = 2

    fun mapFromSnapshot(
        context: Context,
        snapshot: GlobalSnapshot,
        state: WidgetSettingsState,
    ): WidgetUiState {
        val luminance = ColorUtils.calculateLuminance(state.backgroundColor)
        val textColor = if (luminance > 0.5f) Color.BLACK else Color.WHITE
        val secondaryTextColor = if (luminance > 0.5f) Color.DKGRAY else Color.LTGRAY
        val fontMultiplier = FontScaleProvider.getMultiplier(state.fontSize)
        val layoutMode = WidgetLayoutMode.fromValue(state.layoutMode)

        val simUiStates =
            snapshot.simSnapshots
                .filter { it.isActive }
                .sortedBy { it.slot }
                .map { sim ->
                    val simConfig = state.simStates.getOrNull(sim.slot)
                    val simUiState = createSimUiState(context, sim, simConfig, luminance, textColor, secondaryTextColor, fontMultiplier)
                    simUiState.copy(layoutSpec = generateLayoutSpec(simUiState, layoutMode))
                }

        return WidgetUiState(
            layoutMode = state.layoutMode,
            simArrangement = state.simArrangement,
            backgroundColor = state.backgroundColor,
            transparency = state.transparency,
            simUiStates = simUiStates,
            isHorizontal = state.simArrangement == 0,
            showSeparator = simUiStates.size >= 2,
        )
    }

    private fun createSimUiState(
        context: Context,
        sim: com.example.minutewatcher.core.model.SimSnapshot,
        simConfig: com.example.minutewatcher.core.model.SimSettingsState?,
        luminance: Double,
        textColor: Int,
        secondaryTextColor: Int,
        fontMultiplier: Float,
    ): SimUiState {
        val minutesEntity = sim.entities[PackageType.MINUTES]
        val trafficEntity = sim.entities[PackageType.DATA]

        val minutesRemaining = minutesEntity?.remaining ?: 0L
        val minutesLimit = minutesEntity?.limit ?: 0L
        val minutesUsed = minutesEntity?.used ?: 0L

        val trafficLimitBytes = trafficEntity?.limit ?: 0L
        val trafficRemainingBytes = trafficEntity?.remaining ?: 0L
        val trafficUsedBytes = trafficEntity?.used ?: 0L
        val trafficLimitGb = trafficLimitBytes.toFloat() / (1024 * 1024 * 1024)
        val trafficRemainingGb =
            if (trafficLimitBytes > 0) {
                maxOf(0f, trafficRemainingBytes.toFloat() / (1024 * 1024 * 1024))
            } else {
                -1f
            }
        val trafficUsedGb = trafficUsedBytes.toFloat() / (1024 * 1024 * 1024)

        val minutesStatus = ColorProvider.getUsageStatus(minutesRemaining.toFloat(), minutesLimit.toFloat())
        val trafficStatus = ColorProvider.getUsageStatus(trafficRemainingGb, trafficLimitGb)

        val usedLabel = context.getString(R.string.used_text)
        val limitLabel = context.getString(R.string.limit_text)
        val minutesAbbr = context.getString(R.string.minutes_abbr)
        val gbAbbr = context.getString(R.string.gb_abbr)

        return SimUiState(
            slot = sim.slot,
            operatorName = SimUtils.getShortOperatorName(context, sim.operator),
            operatorIconRes = SimUtils.getOperatorIconResource(sim.operator),
            showSim = simConfig?.showSim ?: true,
            showIcon = simConfig?.showIcon ?: true,
            showName = simConfig?.showName ?: true,
            showMinutes = simConfig?.showMinutes ?: true,
            minutesText =
                if (minutesLimit > 0 ||
                    (minutesEntity?.sourceType == DataSourceType.MANUAL)
                ) {
                    minutesRemaining.toString()
                } else {
                    "-"
                },
            minutesColor = getStatusColorInt(minutesStatus, luminance),
            showMinutesLimit = (simConfig?.showMinutesLimit ?: false) && minutesLimit > 0,
            minutesLimitText = context.getString(R.string.fraction_format, minutesLimit.toString()),
            showTraffic = simConfig?.showTraffic ?: true,
            trafficText = if (trafficLimitGb > 0) FormatUtils.formatTrafficGb(trafficRemainingGb) else "-",
            trafficColor = getStatusColorInt(trafficStatus, luminance),
            showTrafficLimit = (simConfig?.showTrafficLimit ?: false) && trafficLimitGb > 0,
            trafficLimitText = context.getString(R.string.fraction_format, FormatUtils.formatTrafficGb(trafficLimitGb)),
            fontMultiplier = fontMultiplier,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            minutesUsedFullText = "$usedLabel: $minutesUsed$minutesAbbr",
            minutesLimitFullText = "$limitLabel: $minutesLimit$minutesAbbr",
            trafficUsedFullText = "$usedLabel: ${FormatUtils.formatTrafficGb(trafficUsedGb)}$gbAbbr",
            trafficLimitFullText = "$limitLabel: ${FormatUtils.formatTrafficGb(trafficLimitGb)}$gbAbbr",
        )
    }

    private fun generateLayoutSpec(
        sim: SimUiState,
        mode: WidgetLayoutMode,
    ): SimLayoutSpec {
        val rows =
            when (mode) {
                WidgetLayoutMode.SINGLE_LINE -> listOf(createSingleLineRow(sim))
                WidgetLayoutMode.TWO_LINES -> createTwoLinesRows(sim)
                WidgetLayoutMode.THREE_LINES -> createThreeLinesRows(sim)
                WidgetLayoutMode.SIX_LINES -> createSixLinesRows(sim)
            }
        return SimLayoutSpec(rows)
    }

    private fun createSingleLineRow(sim: SimUiState): SimLayoutRow {
        val components = mutableListOf<SimLayoutComponent>()
        if (sim.showIcon) {
            components.add(SimLayoutComponent.Icon(sim.operatorIconRes, (SIZE_ICON_SINGLE * sim.fontMultiplier).toInt()))
            components.add(SimLayoutComponent.Spacer(widthDp = SPACING_DEFAULT))
        }
        if (sim.showName) {
            components.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, FONT_SINGLE_NAME * sim.fontMultiplier))
            components.add(SimLayoutComponent.Spacer(widthDp = SPACING_DEFAULT))
        }
        if (sim.showMinutes) {
            components.add(
                SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, FONT_SINGLE_VALUE * sim.fontMultiplier, isBold = true),
            )
        }
        if (sim.showTraffic) {
            components.add(SimLayoutComponent.Spacer(widthDp = SPACING_DEFAULT))
            components.add(
                SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, FONT_SINGLE_VALUE * sim.fontMultiplier, isBold = true),
            )
        }
        return SimLayoutRow(components)
    }

    private fun createTwoLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()
        val row1 = mutableListOf<SimLayoutComponent>()
        if (sim.showIcon) {
            row1.add(SimLayoutComponent.Icon(sim.operatorIconRes, (SIZE_ICON_MULTI * sim.fontMultiplier).toInt()))
            row1.add(SimLayoutComponent.Spacer(widthDp = SPACING_DEFAULT))
        }
        if (sim.showName) row1.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, FONT_MULTI_NAME * sim.fontMultiplier))
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        val row2 = mutableListOf<SimLayoutComponent>()
        if (sim.showMinutes) {
            row2.add(SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
            row2.add(SimLayoutComponent.Spacer(widthDp = SPACING_DEFAULT))
        }
        if (sim.showTraffic) {
            row2.add(SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
        }
        if (row2.isNotEmpty()) rows.add(SimLayoutRow(row2))
        return rows
    }

    private fun createThreeLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()
        val row1 = mutableListOf<SimLayoutComponent>()
        if (sim.showIcon) {
            row1.add(SimLayoutComponent.Icon(sim.operatorIconRes, (SIZE_ICON_MULTI * sim.fontMultiplier).toInt()))
            row1.add(SimLayoutComponent.Spacer(widthDp = SPACING_DEFAULT))
        }
        if (sim.showName) row1.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, FONT_MULTI_NAME * sim.fontMultiplier))
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        if (sim.showMinutes) {
            val row2 = mutableListOf<SimLayoutComponent>()
            row2.add(SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
            if (sim.showMinutesLimit) {
                row2.add(SimLayoutComponent.Spacer(widthDp = SPACING_SMALL))
                row2.add(SimLayoutComponent.Text(sim.minutesLimitText, sim.secondaryTextColor, FONT_LIMIT * sim.fontMultiplier))
            }
            rows.add(SimLayoutRow(row2))
        }
        if (sim.showTraffic) {
            val row3 = mutableListOf<SimLayoutComponent>()
            row3.add(SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
            if (sim.showTrafficLimit) {
                row3.add(SimLayoutComponent.Spacer(widthDp = SPACING_SMALL))
                row3.add(SimLayoutComponent.Text(sim.trafficLimitText, sim.secondaryTextColor, FONT_LIMIT * sim.fontMultiplier))
            }
            rows.add(SimLayoutRow(row3))
        }
        return rows
    }

    private fun createSixLinesRows(sim: SimUiState): List<SimLayoutRow> =
        listOf(
            SimLayoutRow(listOf(SimLayoutComponent.Text(sim.operatorName, sim.textColor, FONT_MULTI_NAME * sim.fontMultiplier))),
            SimLayoutRow(listOf(SimLayoutComponent.Icon(sim.operatorIconRes, (SIZE_ICON_MULTI * sim.fontMultiplier).toInt()))),
            SimLayoutRow(listOf(SimLayoutComponent.Text(sim.minutesUsedFullText, sim.textColor, FONT_FULL * sim.fontMultiplier))),
            SimLayoutRow(listOf(SimLayoutComponent.Text(sim.minutesLimitFullText, sim.secondaryTextColor, FONT_FULL * sim.fontMultiplier))),
            SimLayoutRow(listOf(SimLayoutComponent.Text(sim.trafficUsedFullText, sim.textColor, FONT_FULL * sim.fontMultiplier))),
            SimLayoutRow(listOf(SimLayoutComponent.Text(sim.trafficLimitFullText, sim.secondaryTextColor, FONT_FULL * sim.fontMultiplier))),
        )

    private fun getStatusColorInt(
        status: ColorProvider.UsageStatus,
        backgroundLuminance: Double,
    ): Int {
        val isDark = backgroundLuminance < 0.5
        return when (status) {
            ColorProvider.UsageStatus.CRITICAL -> if (isDark) 0xFFFF6B6B.toInt() else 0xFFD32F2F.toInt()
            ColorProvider.UsageStatus.LOW -> if (isDark) 0xFFFFD93D.toInt() else 0xFFF57C00.toInt()
            ColorProvider.UsageStatus.NORMAL -> if (isDark) 0xFF6BCB77.toInt() else 0xFF388E3C.toInt()
        }
    }
}
