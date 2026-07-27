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

                    val minutesText =
                        if (minutesLimit > 0 || (minutesEntity?.sourceType == DataSourceType.MANUAL)) {
                            minutesRemaining.toString()
                        } else {
                            context.getString(R.string.placeholder_dash)
                        }

                    val trafficText =
                        if (trafficLimitGb > 0) {
                            FormatUtils.formatTrafficGb(trafficRemainingGb)
                        } else {
                            context.getString(R.string.placeholder_dash)
                        }

                    val minutesColor = getStatusColorInt(minutesStatus, luminance)
                    val trafficColor = getStatusColorInt(trafficStatus, luminance)

                    val simUiState =
                        SimUiState(
                            slot = sim.slot,
                            operatorName = SimUtils.getShortOperatorName(context, sim.operator),
                            operatorIconRes = SimUtils.getOperatorIconResource(sim.operator),
                            showSim = simConfig?.showSim ?: true,
                            showIcon = simConfig?.showIcon ?: true,
                            showName = simConfig?.showName ?: true,
                            showMinutes = simConfig?.showMinutes ?: true,
                            minutesText = minutesText,
                            minutesColor = minutesColor,
                            showMinutesLimit = (simConfig?.showMinutesLimit ?: false) && minutesLimit > 0,
                            minutesLimitText = context.getString(R.string.fraction_format, minutesLimit.toString()),
                            showTraffic = simConfig?.showTraffic ?: true,
                            trafficText = trafficText,
                            trafficColor = trafficColor,
                            showTrafficLimit = (simConfig?.showTrafficLimit ?: false) && trafficLimitGb > 0,
                            trafficLimitText = context.getString(R.string.fraction_format, FormatUtils.formatTrafficGb(trafficLimitGb)),
                            fontMultiplier = fontMultiplier,
                            textColor = textColor,
                            secondaryTextColor = secondaryTextColor,
                            // Full text fields for SIX_LINES mode
                            minutesUsedFullText = "$usedLabel: $minutesUsed$minutesAbbr",
                            minutesLimitFullText = "$limitLabel: $minutesLimit$minutesAbbr",
                            trafficUsedFullText = "$usedLabel: ${FormatUtils.formatTrafficGb(trafficUsedGb)}$gbAbbr",
                            trafficLimitFullText = "$limitLabel: ${FormatUtils.formatTrafficGb(trafficLimitGb)}$gbAbbr",
                        )

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

    private fun generateLayoutSpec(
        sim: SimUiState,
        mode: WidgetLayoutMode,
    ): SimLayoutSpec {
        val rows = mutableListOf<SimLayoutRow>()

        when (mode) {
            WidgetLayoutMode.SINGLE_LINE -> {
                val components = mutableListOf<SimLayoutComponent>()
                if (sim.showIcon) {
                    components.add(SimLayoutComponent.Icon(sim.operatorIconRes, (14 * sim.fontMultiplier).toInt()))
                    components.add(SimLayoutComponent.Spacer(widthDp = 4))
                }
                if (sim.showName) {
                    components.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, 10 * sim.fontMultiplier))
                    components.add(SimLayoutComponent.Spacer(widthDp = 4))
                }
                if (sim.showMinutes) {
                    components.add(SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, 12 * sim.fontMultiplier, isBold = true))
                }
                if (sim.showTraffic) {
                    components.add(SimLayoutComponent.Spacer(widthDp = 4))
                    components.add(SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, 12 * sim.fontMultiplier, isBold = true))
                }
                rows.add(SimLayoutRow(components))
            }

            WidgetLayoutMode.TWO_LINES -> {
                // Line 1: Icon & Name
                val row1 = mutableListOf<SimLayoutComponent>()
                if (sim.showIcon) {
                    row1.add(SimLayoutComponent.Icon(sim.operatorIconRes, (16 * sim.fontMultiplier).toInt()))
                    row1.add(SimLayoutComponent.Spacer(widthDp = 4))
                }
                if (sim.showName) {
                    row1.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, 12 * sim.fontMultiplier))
                }
                if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

                // Line 2: Minutes & Traffic
                val row2 = mutableListOf<SimLayoutComponent>()
                if (sim.showMinutes) {
                    row2.add(SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, 14 * sim.fontMultiplier, isBold = true))
                    row2.add(SimLayoutComponent.Spacer(widthDp = 4))
                }
                if (sim.showTraffic) {
                    row2.add(SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, 14 * sim.fontMultiplier, isBold = true))
                }
                if (row2.isNotEmpty()) rows.add(SimLayoutRow(row2))
            }

            WidgetLayoutMode.THREE_LINES -> {
                // Line 1: Header
                val row1 = mutableListOf<SimLayoutComponent>()
                if (sim.showIcon) {
                    row1.add(SimLayoutComponent.Icon(sim.operatorIconRes, (16 * sim.fontMultiplier).toInt()))
                    row1.add(SimLayoutComponent.Spacer(widthDp = 4))
                }
                if (sim.showName) {
                    row1.add(SimLayoutComponent.Text(sim.operatorName, sim.textColor, 12 * sim.fontMultiplier))
                }
                if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

                // Line 2: Minutes
                if (sim.showMinutes) {
                    val row2 = mutableListOf<SimLayoutComponent>()
                    row2.add(SimLayoutComponent.Text(sim.minutesText, sim.minutesColor, 14 * sim.fontMultiplier, isBold = true))
                    if (sim.showMinutesLimit) {
                        row2.add(SimLayoutComponent.Spacer(widthDp = 2))
                        row2.add(SimLayoutComponent.Text(sim.minutesLimitText, sim.secondaryTextColor, 10 * sim.fontMultiplier))
                    }
                    rows.add(SimLayoutRow(row2))
                }

                // Line 3: Traffic
                if (sim.showTraffic) {
                    val row3 = mutableListOf<SimLayoutComponent>()
                    row3.add(SimLayoutComponent.Text(sim.trafficText, sim.trafficColor, 14 * sim.fontMultiplier, isBold = true))
                    if (sim.showTrafficLimit) {
                        row3.add(SimLayoutComponent.Spacer(widthDp = 2))
                        row3.add(SimLayoutComponent.Text(sim.trafficLimitText, sim.secondaryTextColor, 10 * sim.fontMultiplier))
                    }
                    rows.add(SimLayoutRow(row3))
                }
            }

            WidgetLayoutMode.SIX_LINES -> {
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.operatorName, sim.textColor, 12 * sim.fontMultiplier))))
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Icon(sim.operatorIconRes, (16 * sim.fontMultiplier).toInt()))))
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.minutesUsedFullText, sim.textColor, 11 * sim.fontMultiplier))))
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.minutesLimitFullText, sim.secondaryTextColor, 11 * sim.fontMultiplier))))
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.trafficUsedFullText, sim.textColor, 11 * sim.fontMultiplier))))
                rows.add(SimLayoutRow(listOf(SimLayoutComponent.Text(sim.trafficLimitFullText, sim.secondaryTextColor, 11 * sim.fontMultiplier))))
            }
        }

        return SimLayoutSpec(rows)
    }

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
