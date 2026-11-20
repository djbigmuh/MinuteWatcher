package com.github.djbigmuh.minutewatcher.core.android.util

import com.github.djbigmuh.minutewatcher.core.model.SimLayoutComponent
import com.github.djbigmuh.minutewatcher.core.model.SimLayoutRow
import com.github.djbigmuh.minutewatcher.core.model.SimLayoutSpec
import com.github.djbigmuh.minutewatcher.core.model.SimUiState
import com.github.djbigmuh.minutewatcher.core.model.WidgetLayoutMode
import com.github.djbigmuh.minutewatcher.core.model.util.WidgetDimens

object WidgetLayoutSpecBuilder {
    fun generateLayoutSpec(
        sim: SimUiState,
        mode: WidgetLayoutMode,
    ): SimLayoutSpec {
        val rows =
            when (mode) {
                WidgetLayoutMode.SINGLE_LINE -> listOf(createSingleLineRow(sim))
                WidgetLayoutMode.TWO_LINES -> createTwoLinesRows(sim)
                WidgetLayoutMode.THREE_LINES -> createThreeLinesRows(sim)
                WidgetLayoutMode.SIX_LINES -> createSixLinesRows(sim)
                WidgetLayoutMode.SIMPLE -> listOf(createSimpleRow(sim))
            }
        return SimLayoutSpec(rows)
    }

    private fun createSimpleRow(sim: SimUiState): SimLayoutRow {
        val components = mutableListOf<SimLayoutComponent>()
        if (sim.showMinutes) {
            addValueWithLimit(
                components,
                sim.minutesText,
                sim.minutesColor,
                sim.showMinutesLimit,
                sim.minutesLimitText,
                sim.secondaryTextColor,
                sim.minutesSuffix,
                WidgetDimens.FONT_SINGLE_VALUE * sim.fontMultiplier,
                sim.fontMultiplier,
            )
        }
        if (sim.showTraffic) {
            if (components.isNotEmpty()) {
                components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
            }
            addValueWithLimit(
                components,
                sim.trafficText,
                sim.trafficColor,
                sim.showTrafficLimit,
                sim.trafficLimitText,
                sim.secondaryTextColor,
                sim.trafficSuffix,
                WidgetDimens.FONT_SINGLE_VALUE * sim.fontMultiplier,
                sim.fontMultiplier,
            )
        }
        return SimLayoutRow(components)
    }

    private fun createSingleLineRow(sim: SimUiState): SimLayoutRow {
        val components = mutableListOf<SimLayoutComponent>()
        addIconAndName(components, sim, WidgetDimens.SIZE_ICON_SINGLE, WidgetDimens.FONT_SINGLE_NAME)
        addUsageInfo(
            components,
            sim,
            WidgetDimens.FONT_SINGLE_VALUE,
            addInitialSpacer = true,
        )
        return SimLayoutRow(components)
    }

    private fun createTwoLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()
        val row1 = mutableListOf<SimLayoutComponent>()
        addIconAndName(row1, sim, WidgetDimens.SIZE_ICON_MULTI, WidgetDimens.FONT_MULTI_NAME)
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        val row2 = mutableListOf<SimLayoutComponent>()
        addUsageInfo(
            row2,
            sim,
            WidgetDimens.FONT_MULTI_VALUE,
            addInitialSpacer = false,
        )
        if (row2.isNotEmpty()) rows.add(SimLayoutRow(row2))
        return rows
    }

    private fun addIconAndName(
        components: MutableList<SimLayoutComponent>,
        sim: SimUiState,
        iconSizeBase: Int,
        fontSizeBase: Float,
    ) {
        if (sim.showIcon) {
            val size = (iconSizeBase * sim.fontMultiplier).toInt()
            components.add(
                SimLayoutComponent.Icon(
                    iconRes = sim.operatorIconRes,
                    sizeDp = size,
                ),
            )
            components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
        }
        if (sim.showName) {
            val nameText =
                SimLayoutComponent.Text(
                    text = sim.operatorName,
                    color = sim.textColor,
                    fontSizeSp = fontSizeBase * sim.fontMultiplier,
                )
            components.add(nameText)
            if (iconSizeBase == WidgetDimens.SIZE_ICON_SINGLE) {
                components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
            }
        }
    }

    private fun addUsageInfo(
        components: MutableList<SimLayoutComponent>,
        sim: SimUiState,
        fontSizeBase: Float,
        addInitialSpacer: Boolean,
    ) {
        if (sim.showMinutes) {
            if (addInitialSpacer && components.isNotEmpty()) {
                components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
            }
            addValueWithLimit(
                components,
                sim.minutesText,
                sim.minutesColor,
                sim.showMinutesLimit,
                sim.minutesLimitText,
                sim.secondaryTextColor,
                sim.minutesSuffix,
                fontSizeBase * sim.fontMultiplier,
                sim.fontMultiplier,
            )
        }
        if (sim.showTraffic) {
            if (components.isNotEmpty()) {
                components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
            }
            addValueWithLimit(
                components,
                sim.trafficText,
                sim.trafficColor,
                sim.showTrafficLimit,
                sim.trafficLimitText,
                sim.secondaryTextColor,
                sim.trafficSuffix,
                fontSizeBase * sim.fontMultiplier,
                sim.fontMultiplier,
            )
        }
    }

    private fun addValueWithLimit(
        components: MutableList<SimLayoutComponent>,
        valueText: String,
        valueColor: Int,
        showLimit: Boolean,
        limitText: String,
        limitColor: Int,
        suffix: String,
        valueSize: Float,
        fontMultiplier: Float,
    ) {
        components.add(
            SimLayoutComponent.Text(
                text = valueText,
                color = valueColor,
                fontSizeSp = valueSize,
                isBold = true,
            ),
        )
        if (showLimit && limitText.isNotEmpty()) {
            components.add(
                SimLayoutComponent.Text(
                    text = limitText,
                    color = limitColor,
                    fontSizeSp = WidgetDimens.FONT_LIMIT * fontMultiplier,
                ),
            )
        }
        if (suffix.isNotEmpty()) {
            components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_SMALL))
            components.add(
                SimLayoutComponent.Text(
                    text = suffix,
                    color = limitColor,
                    fontSizeSp = WidgetDimens.FONT_LIMIT * fontMultiplier,
                ),
            )
        }
    }

    private fun createThreeLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()
        val row1 = mutableListOf<SimLayoutComponent>()
        addIconAndName(row1, sim, WidgetDimens.SIZE_ICON_MULTI, WidgetDimens.FONT_MULTI_NAME)
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        if (sim.showMinutes) {
            val row2 = mutableListOf<SimLayoutComponent>()
            addValueWithLimit(
                row2,
                sim.minutesText,
                sim.minutesColor,
                sim.showMinutesLimit,
                sim.minutesLimitText,
                sim.secondaryTextColor,
                sim.minutesSuffix,
                WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier,
                sim.fontMultiplier,
            )
            rows.add(SimLayoutRow(row2))
        }
        if (sim.showTraffic) {
            val row3 = mutableListOf<SimLayoutComponent>()
            addValueWithLimit(
                row3,
                sim.trafficText,
                sim.trafficColor,
                sim.showTrafficLimit,
                sim.trafficLimitText,
                sim.secondaryTextColor,
                sim.trafficSuffix,
                WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier,
                sim.fontMultiplier,
            )
            rows.add(SimLayoutRow(row3))
        }
        return rows
    }

    private fun createSixLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()
        if (sim.showName) {
            val nameText =
                SimLayoutComponent.Text(
                    text = sim.operatorName,
                    color = sim.textColor,
                    fontSizeSp = WidgetDimens.FONT_MULTI_NAME * sim.fontMultiplier,
                )
            rows.add(SimLayoutRow(listOf(nameText)))
        }
        if (sim.showIcon) {
            val size = (WidgetDimens.SIZE_ICON_MULTI * sim.fontMultiplier).toInt()
            rows.add(
                SimLayoutRow(
                    listOf(
                        SimLayoutComponent.Icon(
                            iconRes = sim.operatorIconRes,
                            sizeDp = size,
                        ),
                    ),
                ),
            )
        }
        if (sim.showMinutes) {
            addSixLinesUsageRow(rows, sim.minutesUsedFullText, sim.textColor, sim.fontMultiplier)
            if (sim.showMinutesLimit) {
                addSixLinesUsageRow(rows, sim.minutesLimitFullText, sim.secondaryTextColor, sim.fontMultiplier)
            }
        }
        if (sim.showTraffic) {
            addSixLinesUsageRow(rows, sim.trafficUsedFullText, sim.textColor, sim.fontMultiplier)
            if (sim.showTrafficLimit) {
                addSixLinesUsageRow(rows, sim.trafficLimitFullText, sim.secondaryTextColor, sim.fontMultiplier)
            }
        }
        return rows
    }

    private fun addSixLinesUsageRow(
        rows: MutableList<SimLayoutRow>,
        text: String,
        color: Int,
        fontMultiplier: Float,
    ) {
        val textComp =
            SimLayoutComponent.Text(
                text = text,
                color = color,
                fontSizeSp = WidgetDimens.FONT_FULL * fontMultiplier,
            )
        rows.add(SimLayoutRow(listOf(textComp)))
    }
}
