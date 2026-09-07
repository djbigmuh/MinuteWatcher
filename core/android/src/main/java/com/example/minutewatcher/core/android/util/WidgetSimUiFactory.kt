package com.example.minutewatcher.core.android.util

import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.WidgetStrings
import com.example.minutewatcher.core.model.util.ColorProvider

/**
 * Factory to create [SimUiState] from a snapshot and settings.
 *
 * **Visibility Contract:**
 * - Suffix "min" is visible ⇔ [SimSettingsState.showMinutes] == true
 * - Minutes limit visible ⇔ [SimSettingsState.showMinutesLimit] == true
 *   AND [SimSettingsState.showMinutes] == true (checked in layout spec builder)
 * - Suffix "GB" visible ⇔ [SimSettingsState.showTraffic] == true
 * - Traffic limit visible ⇔ [SimSettingsState.showTrafficLimit] == true
 *   AND [SimSettingsState.showTraffic] == true (checked in layout spec builder)
 *
 * [WidgetLayoutMode] affects ONLY font size and layout, but DOES NOT override
 * user visibility settings. If a user enabled minutes, the suffix MUST be visible
 * in any layout mode.
 */
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
        simListLoadFailed: Boolean = false,
    ): SimUiState {
        val minutesEntity = sim.entities[PackageType.MINUTES]
        val trafficEntity = sim.entities[PackageType.DATA]

        val minData = mapMinutesData(strings, minutesEntity, simConfig, luminance)
        val trafData = mapTrafficData(strings, trafficEntity, simConfig, luminance)

        return SimUiState(
            slot = sim.slot,
            operatorName = operatorName,
            operatorIconRes = SimUtils.getOperatorIconResource(sim.operatorName),
            showSim = simConfig?.showSim ?: simListLoadFailed,
            showIcon = simConfig?.showIcon ?: true,
            showName = simConfig?.showName ?: true,
            showMinutes = minData.show,
            minutesText = minData.text,
            minutesSuffix = minData.suffix,
            minutesColor = minData.color,
            showMinutesLimit = minData.showLimit,
            minutesLimitText = minData.limitText,
            showTraffic = trafData.show,
            trafficText = trafData.text,
            trafficSuffix = trafData.suffix,
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
        val suffix: String,
        val color: Int,
        val showLimit: Boolean,
        val limitText: String,
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

        val showMinutes = (config?.showMinutes ?: true) && entity != null && hasData
        val showLimit = (config?.showMinutesLimit ?: false) && limit > 0

        return PackageUiData(
            show = showMinutes,
            text = rem.toString(),
            suffix = strings.minutesAbbr,
            color = WidgetColorResolver.getStatusColor(status, luminance),
            showLimit = showLimit,
            limitText = if (showLimit) strings.fractionFormat.format(limit) else "",
            usedFull = "${strings.usedText}: ${formatWithSuffix(used.toString(), strings.minutesAbbr)}",
            limitFull = "${strings.limitText}: ${formatWithSuffix(limit.toString(), strings.minutesAbbr)}",
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
        val remGb = calculateRemainingGb(entity, limitBytes)
        val usedGb = ((entity?.used ?: 0L).toDouble() / Constants.BYTES_PER_GB).toFloat()

        val status = ColorProvider.getUsageStatus(remGb, limitGb)
        val showTraffic = (config?.showTraffic ?: true) && entity != null && (limitGb > 0f || usedGb > 0f)
        val showLimit = (config?.showTrafficLimit ?: false) && limitGb > 0f

        return PackageUiData(
            show = showTraffic,
            text = formatTrafficValue(entity, limitGb, remGb),
            suffix = strings.gbAbbr,
            color = WidgetColorResolver.getStatusColor(status, luminance),
            showLimit = showLimit,
            limitText = if (showLimit) strings.fractionFormat.format(FormatUtils.formatTrafficGb(limitGb)) else "",
            usedFull = "${strings.usedText}: ${
                formatWithSuffix(
                    FormatUtils.formatTrafficGb(usedGb),
                    strings.gbAbbr,
                )
            }",
            limitFull = "${strings.limitText}: ${
                formatWithSuffix(FormatUtils.formatTrafficGb(limitGb), strings.gbAbbr)
            }",
        )
    }

    private fun calculateRemainingGb(
        entity: SemanticEntity?,
        limitBytes: Long,
    ): Float {
        if (limitBytes <= 0) return -1f
        val remBytes = entity?.remaining ?: 0L
        return maxOf(0f, (remBytes.toDouble() / Constants.BYTES_PER_GB).toFloat())
    }

    private fun formatTrafficValue(
        entity: SemanticEntity?,
        limitGb: Float,
        remGb: Float,
    ): String {
        if (entity == null || limitGb <= 0f) return "-"
        return FormatUtils.formatTrafficGb(remGb)
    }

    private fun formatWithSuffix(
        value: String,
        suffix: String,
    ): String {
        if (suffix.isEmpty()) return value
        return if (suffix.startsWith(" ") || suffix.startsWith("\u00A0")) {
            "$value$suffix"
        } else {
            "$value $suffix"
        }
    }
}
