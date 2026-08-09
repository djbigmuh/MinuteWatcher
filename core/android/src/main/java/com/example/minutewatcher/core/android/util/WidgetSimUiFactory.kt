package com.example.minutewatcher.core.android.util

import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetStrings
import com.example.minutewatcher.core.model.util.ColorProvider

object WidgetSimUiFactory {
    fun createSimUiState(
        strings: WidgetStrings,
        sim: SimSnapshot,
        simConfig: SimSettingsState?,
        operatorName: String,
        luminance: Double,
        textColor: Int,
        secondaryTextColor: Int,
        fontMultiplier: Float,
    ): SimUiState {
        val minutesEntity = sim.entities[PackageType.MINUTES]
        val trafficEntity = sim.entities[PackageType.DATA]

        val minData = mapMinutesData(strings, minutesEntity, simConfig, luminance)
        val trafData = mapTrafficData(strings, trafficEntity, simConfig, luminance)

        return SimUiState(
            slot = sim.slot,
            operatorName = operatorName,
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
        strings: WidgetStrings,
        entity: SemanticEntity?,
        config: SimSettingsState?,
        luminance: Double,
    ): PackageUiData {
        val limit = entity?.limit ?: 0L
        val used = entity?.used ?: 0L
        val rem = entity?.remaining ?: 0L
        val hasData = limit > 0 || used > 0
        val status = ColorProvider.getUsageStatus(rem.toFloat(), limit.toFloat())

        return PackageUiData(
            show = (config?.showMinutes ?: true) && entity != null && hasData,
            text =
                if (entity != null &&
                    (limit > 0 || entity.sourceType == DataSourceType.MANUAL)
                ) {
                    rem.toString()
                } else {
                    "-"
                },
            color = WidgetColorResolver.getStatusColor(status, luminance),
            showLimit = (config?.showMinutesLimit ?: false) && limit > 0,
            limitText = strings.fractionFormat.format(limit.toString()),
            hasData = hasData,
            usedFull = "${strings.usedText}: $used${strings.minutesAbbr}",
            limitFull = "${strings.limitText}: $limit${strings.minutesAbbr}",
        )
    }

    private fun mapTrafficData(
        strings: WidgetStrings,
        entity: SemanticEntity?,
        config: SimSettingsState?,
        luminance: Double,
    ): PackageUiData {
        val limitBytes = entity?.limit ?: 0L
        val limitGb = (limitBytes.toDouble() / Constants.BYTES_PER_GB).toFloat()
        val remGb =
            if (limitBytes >
                0
            ) {
                maxOf(0f, ((entity?.remaining ?: 0L).toDouble() / Constants.BYTES_PER_GB).toFloat())
            } else {
                -1f
            }
        val usedGb = ((entity?.used ?: 0L).toDouble() / Constants.BYTES_PER_GB).toFloat()
        val hasData = limitGb > 0f || usedGb > 0f
        val status = ColorProvider.getUsageStatus(remGb, limitGb)

        return PackageUiData(
            show = (config?.showTraffic ?: true) && entity != null && hasData,
            text = if (entity != null && limitGb > 0f) FormatUtils.formatTrafficGb(remGb) else "-",
            color = WidgetColorResolver.getStatusColor(status, luminance),
            showLimit = (config?.showTrafficLimit ?: false) && limitGb > 0f,
            limitText = strings.fractionFormat.format(FormatUtils.formatTrafficGb(limitGb)),
            hasData = hasData,
            usedFull = "${strings.usedText}: ${FormatUtils.formatTrafficGb(usedGb)}${strings.gbAbbr}",
            limitFull = "${strings.limitText}: ${FormatUtils.formatTrafficGb(limitGb)}${strings.gbAbbr}",
        )
    }
}
