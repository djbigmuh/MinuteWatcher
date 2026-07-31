package com.example.minutewatcher.core.android.util

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutRow
import com.example.minutewatcher.core.model.SimLayoutSpec
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetSettingsState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.model.util.ColorProvider
import com.example.minutewatcher.core.model.util.FontScaleProvider
import com.example.minutewatcher.core.model.util.UsageColors
import com.example.minutewatcher.core.model.util.UsageStatus
import com.example.minutewatcher.core.model.util.WidgetDimens

object WidgetUiMapper {

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
                    val simConfig = state.simStates[sim.slot]
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
            useSystemColors = state.transparency > 80,
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

        val minData = mapMinutesData(context, minutesEntity, simConfig, luminance)
        val trafData = mapTrafficData(context, trafficEntity, simConfig, luminance)

        return SimUiState(
            slot = sim.slot,
            operatorName = SimUtils.getShortOperatorName(context, sim.operator),
            operatorIconRes = SimUtils.getOperatorIconResource(sim.operator),
            showSim = simConfig?.showSim ?: true,
            showIcon = simConfig?.showIcon ?: true,
            showName = simConfig?.showName ?: true,
            showMinutes = minData.show,
            minutesText = minData.text,
            minutesColor = minData.color,
            showMinutesLimit = minData.showLimit,
            minutesLimitText = minData.limitText,
            showTraffic = trafData.show,
            trafficText = trafData.text,
            trafficColor = trafData.color,
            showTrafficLimit = trafData.showLimit,
            trafficLimitText = trafData.limitText,
            fontMultiplier = fontMultiplier,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            hasMinutesData = minData.hasData,
            hasTrafficData = trafData.hasData,
            minutesUsedFullText = minData.usedFull,
            minutesLimitFullText = minData.limitFull,
            trafficUsedFullText = trafData.usedFull,
            trafficLimitFullText = trafData.limitFull,
        )
    }

    private data class PackageUiData(
        val show: Boolean,
        val text: String,
        val color: Int,
        val showLimit: Boolean,
        val limitText: String,
        val hasData: Boolean,
        val usedFull: String,
        val limitFull: String,
    )

    private fun mapMinutesData(
        context: Context,
        entity: com.example.minutewatcher.core.model.SemanticEntity?,
        config: com.example.minutewatcher.core.model.SimSettingsState?,
        luminance: Double,
    ): PackageUiData {
        val limit = entity?.limit ?: 0L
        val used = entity?.used ?: 0L
        val rem = entity?.remaining ?: 0L
        val hasData = limit > 0 || used > 0
        val status = ColorProvider.getUsageStatus(rem.toFloat(), limit.toFloat())
        
        return PackageUiData(
            show = (config?.showMinutes ?: true) && entity != null && hasData,
            text = if (entity != null && (limit > 0 || entity.sourceType == DataSourceType.MANUAL)) rem.toString() else "-",
            color = getStatusColorInt(status, luminance),
            showLimit = (config?.showMinutesLimit ?: false) && limit > 0,
            limitText = context.getString(R.string.fraction_format, limit.toString()),
            hasData = hasData,
            usedFull = "${context.getString(R.string.used_text)}: $used${context.getString(R.string.minutes_abbr)}",
            limitFull = "${context.getString(R.string.limit_text)}: $limit${context.getString(R.string.minutes_abbr)}",
        )
    }

    private fun mapTrafficData(
        context: Context,
        entity: com.example.minutewatcher.core.model.SemanticEntity?,
        config: com.example.minutewatcher.core.model.SimSettingsState?,
        luminance: Double,
    ): PackageUiData {
        val limitBytes = entity?.limit ?: 0L
        val limitGb = limitBytes.toFloat() / Constants.BYTES_PER_GB
        val remGb = if (limitBytes > 0) maxOf(0f, (entity?.remaining ?: 0L).toFloat() / Constants.BYTES_PER_GB) else -1f
        val usedGb = (entity?.used ?: 0L).toFloat() / Constants.BYTES_PER_GB
        val hasData = limitGb > 0f || usedGb > 0f
        val status = ColorProvider.getUsageStatus(remGb, limitGb)

        return PackageUiData(
            show = (config?.showTraffic ?: true) && entity != null && hasData,
            text = if (entity != null && limitGb > 0f) FormatUtils.formatTrafficGb(remGb) else "-",
            color = getStatusColorInt(status, luminance),
            showLimit = (config?.showTrafficLimit ?: false) && limitGb > 0f,
            limitText = context.getString(R.string.fraction_format, FormatUtils.formatTrafficGb(limitGb)),
            hasData = hasData,
            usedFull = "${context.getString(R.string.used_text)}: ${FormatUtils.formatTrafficGb(usedGb)}${context.getString(R.string.gb_abbr)}",
            limitFull = "${context.getString(R.string.limit_text)}: ${FormatUtils.formatTrafficGb(limitGb)}${context.getString(R.string.gb_abbr)}",
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
            components.add(SimLayoutComponent.Icon(sim.operatorIconRes, (WidgetDimens.SIZE_ICON_SINGLE * sim.fontMultiplier).toInt()))
            components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
        }
        if (sim.showName) {
            components.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, WidgetDimens.FONT_SINGLE_NAME * sim.fontMultiplier))
            components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
        }
        if (sim.showMinutes) {
            components.add(
                SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, WidgetDimens.FONT_SINGLE_VALUE * sim.fontMultiplier, isBold = true),
            )
        }
        if (sim.showTraffic) {
            components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
            components.add(
                SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, WidgetDimens.FONT_SINGLE_VALUE * sim.fontMultiplier, isBold = true),
            )
        }
        return SimLayoutRow(components)
    }

    private fun createTwoLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()
        val row1 = mutableListOf<SimLayoutComponent>()
        if (sim.showIcon) {
            row1.add(SimLayoutComponent.Icon(sim.operatorIconRes, (WidgetDimens.SIZE_ICON_MULTI * sim.fontMultiplier).toInt()))
            row1.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
        }
        if (sim.showName) row1.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, WidgetDimens.FONT_MULTI_NAME * sim.fontMultiplier))
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        val row2 = mutableListOf<SimLayoutComponent>()
        if (sim.showMinutes) {
            row2.add(SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
            row2.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
        }
        if (sim.showTraffic) {
            row2.add(SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
        }
        if (row2.isNotEmpty()) rows.add(SimLayoutRow(row2))
        return rows
    }

    private fun createThreeLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()
        val row1 = mutableListOf<SimLayoutComponent>()
        if (sim.showIcon) {
            row1.add(SimLayoutComponent.Icon(sim.operatorIconRes, (WidgetDimens.SIZE_ICON_MULTI * sim.fontMultiplier).toInt()))
            row1.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
        }
        if (sim.showName) row1.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, WidgetDimens.FONT_MULTI_NAME * sim.fontMultiplier))
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        if (sim.showMinutes) {
            val row2 = mutableListOf<SimLayoutComponent>()
            row2.add(SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
            if (sim.showMinutesLimit) {
                row2.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_SMALL))
                row2.add(SimLayoutComponent.Text(sim.minutesLimitText, sim.secondaryTextColor, WidgetDimens.FONT_LIMIT * sim.fontMultiplier))
            }
            rows.add(SimLayoutRow(row2))
        }
        if (sim.showTraffic) {
            val row3 = mutableListOf<SimLayoutComponent>()
            row3.add(SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier, isBold = true))
            if (sim.showTrafficLimit) {
                row3.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_SMALL))
                row3.add(SimLayoutComponent.Text(sim.trafficLimitText, sim.secondaryTextColor, WidgetDimens.FONT_LIMIT * sim.fontMultiplier))
            }
            rows.add(SimLayoutRow(row3))
        }
        return rows
    }

    private fun createSixLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()

        if (sim.showName) {
            rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.operatorName, sim.textColor, WidgetDimens.FONT_MULTI_NAME * sim.fontMultiplier))))
        }

        if (sim.showIcon) {
            rows.add(SimLayoutRow(listOf(SimLayoutComponent.Icon(sim.operatorIconRes, (WidgetDimens.SIZE_ICON_MULTI * sim.fontMultiplier).toInt()))))
        }

        if (sim.showMinutes) {
            rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.minutesUsedFullText, sim.textColor, WidgetDimens.FONT_FULL * sim.fontMultiplier))))
            if (sim.showMinutesLimit) {
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.minutesLimitFullText, sim.secondaryTextColor, WidgetDimens.FONT_FULL * sim.fontMultiplier))))
            }
        }

        if (sim.showTraffic) {
            rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.trafficUsedFullText, sim.textColor, WidgetDimens.FONT_FULL * sim.fontMultiplier))))
            if (sim.showTrafficLimit) {
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.trafficLimitFullText, sim.secondaryTextColor, WidgetDimens.FONT_FULL * sim.fontMultiplier))))
            }
        }

        return rows
    }

    private fun getStatusColorInt(
        status: UsageStatus,
        backgroundLuminance: Double,
    ): Int {
        val isDark = backgroundLuminance < 0.5
        return UsageColors.get(status, isDark)
    }
}
