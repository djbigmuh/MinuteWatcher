package com.example.minutewatcher.feature.widget.util

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.*
import com.example.minutewatcher.core.model.util.*

object WidgetUiMapper {

    private fun createDefaultSimConfig(slot: Int) = SimWidgetConfig(
        slot = slot,
        showSim = true,
        showIcon = true,
        showName = true,
        showMinutes = true,
        showMinutesLimit = false,
        showTraffic = true,
        showTrafficLimit = false
    )

    fun snapshotToUiState(
        context: Context,
        snapshot: GlobalSnapshot,
        config: WidgetConfig
    ): WidgetUiState {
        val luminance = ColorUtils.calculateLuminance(config.backgroundColor)
        val textColor = if (luminance > 0.5f) Color.BLACK else Color.WHITE
        val secondaryTextColor = if (luminance > 0.5f) Color.DKGRAY else Color.LTGRAY
        val fontMultiplier = FontScaleProvider.getMultiplier(config.fontSize)

        val simUiStates = snapshot.simSnapshots
            .filter { it.isActive }
            .sortedBy { it.slot }
            .map { sim ->
            val simConfig = config.simConfigs.find { it.slot == sim.slot } ?: createDefaultSimConfig(sim.slot)

            val minutesEntity = sim.entities[PackageType.MINUTES]
            val trafficEntity = sim.entities[PackageType.DATA]

            val minutesRemaining = minutesEntity?.remaining ?: 0L
            val minutesLimit = minutesEntity?.limit ?: 0L
            
            val trafficLimitBytes = trafficEntity?.limit ?: 0L
            val trafficRemainingBytes = trafficEntity?.remaining ?: 0L
            val trafficLimitGb = trafficLimitBytes.toFloat() / (1024 * 1024 * 1024)
            val trafficRemainingGb = if (trafficLimitBytes > 0) trafficRemainingBytes.toFloat() / (1024 * 1024 * 1024) else -1f

            val minutesStatus = ColorProvider.getUsageStatus(minutesRemaining.toFloat(), minutesLimit.toFloat())
            val trafficStatus = ColorProvider.getUsageStatus(trafficRemainingGb, trafficLimitGb)

            SimUiState(
                slot = sim.slot,
                operatorName = SimUtils.getShortOperatorName(context, sim.operator),
                operatorIconRes = SimUtils.getOperatorIconResource(sim.operator),
                showSim = simConfig.showSim,
                showIcon = simConfig.showIcon,
                showName = simConfig.showName,
                showMinutes = simConfig.showMinutes,
                minutesText = if (minutesLimit > 0 || (minutesEntity?.sourceType == DataSourceType.MANUAL)) minutesRemaining.toString() else context.getString(R.string.placeholder_dash),
                minutesColor = getStatusColorInt(minutesStatus, luminance),
                showMinutesLimit = simConfig.showMinutesLimit && minutesLimit > 0,
                minutesLimitText = context.getString(R.string.fraction_format, minutesLimit.toString()),
                showTraffic = simConfig.showTraffic,
                trafficText = if (trafficLimitGb > 0) FormatUtils.formatTrafficGb(trafficRemainingGb) else context.getString(R.string.placeholder_dash),
                trafficColor = getStatusColorInt(trafficStatus, luminance),
                showTrafficLimit = simConfig.showTrafficLimit && trafficLimitGb > 0,
                trafficLimitText = context.getString(R.string.fraction_format, FormatUtils.formatTrafficGb(trafficLimitGb)),
                fontMultiplier = fontMultiplier,
                textColor = textColor,
                secondaryTextColor = secondaryTextColor
            )
        }

        return WidgetUiState(
            layoutMode = config.layoutMode,
            simArrangement = config.simArrangement,
            backgroundColor = config.backgroundColor,
            transparency = config.transparency,
            simUiStates = simUiStates
        )
    }

    private fun getStatusColorInt(status: ColorProvider.UsageStatus, backgroundLuminance: Double): Int {
        val isDark = backgroundLuminance < 0.5
        return when (status) {
            ColorProvider.UsageStatus.CRITICAL -> if (isDark) 0xFFFF6B6B.toInt() else 0xFFD32F2F.toInt()
            ColorProvider.UsageStatus.LOW -> if (isDark) 0xFFFFD93D.toInt() else 0xFFF57C00.toInt()
            ColorProvider.UsageStatus.NORMAL -> if (isDark) 0xFF6BCB77.toInt() else 0xFF388E3C.toInt()
        }
    }

    fun mapFromSnapshot(
        context: Context,
        snapshot: GlobalSnapshot,
        state: WidgetSettingsState
    ): WidgetUiState {
        val config = WidgetConfig(
            layoutMode = state.layoutMode,
            simArrangement = state.simArrangement,
            fontSize = state.fontSize,
            transparency = state.transparency,
            backgroundColor = state.backgroundColor,
            simConfigs = state.simStates.mapIndexed { index, sim ->
                SimWidgetConfig(
                    slot = index,
                    showSim = sim.showSim,
                    showIcon = sim.showIcon,
                    showName = sim.showName,
                    showMinutes = sim.showMinutes,
                    showMinutesLimit = sim.showMinutesLimit,
                    showTraffic = sim.showTraffic,
                    showTrafficLimit = sim.showTrafficLimit
                )
            }
        )
        return snapshotToUiState(context, snapshot, config)
    }
}
