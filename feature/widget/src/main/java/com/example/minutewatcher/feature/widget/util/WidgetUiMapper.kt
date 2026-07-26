package com.example.minutewatcher.feature.widget.util

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.example.minutewatcher.core.model.*
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.util.*

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
                    val trafficRemainingGb = if (trafficLimitBytes > 0) trafficRemainingBytes.toFloat() / (1024 * 1024 * 1024) else -1f
                    val trafficUsedGb = trafficUsedBytes.toFloat() / (1024 * 1024 * 1024)

                    val minutesStatus = ColorProvider.getUsageStatus(minutesRemaining.toFloat(), minutesLimit.toFloat())
                    val trafficStatus = ColorProvider.getUsageStatus(trafficRemainingGb, trafficLimitGb)

                    val usedLabel = context.getString(R.string.used_text)
                    val limitLabel = context.getString(R.string.limit_text)
                    val minutesAbbr = context.getString(R.string.minutes_abbr)
                    val gbAbbr = context.getString(R.string.gb_abbr)

                    SimUiState(
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
                                context.getString(R.string.placeholder_dash)
                            },
                        minutesColor = getStatusColorInt(minutesStatus, luminance),
                        showMinutesLimit = (simConfig?.showMinutesLimit ?: false) && minutesLimit > 0,
                        minutesLimitText = context.getString(R.string.fraction_format, minutesLimit.toString()),
                        showTraffic = simConfig?.showTraffic ?: true,
                        trafficText =
                            if (trafficLimitGb > 0) {
                                FormatUtils.formatTrafficGb(trafficRemainingGb)
                            } else {
                                context.getString(R.string.placeholder_dash)
                            },
                        trafficColor = getStatusColorInt(trafficStatus, luminance),
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
                }

        return WidgetUiState(
            layoutMode = state.layoutMode,
            simArrangement = state.simArrangement,
            backgroundColor = state.backgroundColor,
            transparency = state.transparency,
            simUiStates = simUiStates,
        )
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
