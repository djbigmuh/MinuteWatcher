package com.example.minutewatcher.core.android.util

import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutRow
import com.example.minutewatcher.core.model.SimLayoutSpec
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetLayoutMode
import com.example.minutewatcher.core.model.util.WidgetDimens

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
                SimLayoutComponent.Text(
                    sim.minutesText,
                    sim.minutesColor,
                    WidgetDimens.FONT_SINGLE_VALUE * sim.fontMultiplier,
                    isBold = true,
                ),
            )
        }
        if (sim.showTraffic) {
            components.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
            components.add(
                SimLayoutComponent.Text(
                    sim.trafficText,
                    sim.trafficColor,
                    WidgetDimens.FONT_SINGLE_VALUE * sim.fontMultiplier,
                    isBold = true,
                ),
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
        if (sim.showName) {
            row1.add(
                SimLayoutComponent.Text(
                    sim.operatorName,
                    sim.textColor,
                    WidgetDimens.FONT_MULTI_NAME * sim.fontMultiplier,
                ),
            )
        }
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        val row2 = mutableListOf<SimLayoutComponent>()
        if (sim.showMinutes) {
            row2.add(
                SimLayoutComponent.Text(
                    sim.minutesText,
                    sim.minutesColor,
                    WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier,
                    isBold = true,
                ),
            )
            row2.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_DEFAULT))
        }
        if (sim.showTraffic) {
            row2.add(
                SimLayoutComponent.Text(
                    sim.trafficText,
                    sim.trafficColor,
                    WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier,
                    isBold = true,
                ),
            )
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
        if (sim.showName) {
            row1.add(
                SimLayoutComponent.Text(
                    sim.operatorName,
                    sim.textColor,
                    WidgetDimens.FONT_MULTI_NAME * sim.fontMultiplier,
                ),
            )
        }
        if (row1.isNotEmpty()) rows.add(SimLayoutRow(row1))

        if (sim.showMinutes) {
            val row2 = mutableListOf<SimLayoutComponent>()
            row2.add(
                SimLayoutComponent.Text(
                    sim.minutesText,
                    sim.minutesColor,
                    WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier,
                    isBold = true,
                ),
            )
            if (sim.showMinutesLimit) {
                row2.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_SMALL))
                row2.add(
                    SimLayoutComponent.Text(sim.minutesLimitText, sim.secondaryTextColor, WidgetDimens.FONT_LIMIT * sim.fontMultiplier),
                )
            }
            rows.add(SimLayoutRow(row2))
        }
        if (sim.showTraffic) {
            val row3 = mutableListOf<SimLayoutComponent>()
            row3.add(
                SimLayoutComponent.Text(
                    sim.trafficText,
                    sim.trafficColor,
                    WidgetDimens.FONT_MULTI_VALUE * sim.fontMultiplier,
                    isBold = true,
                ),
            )
            if (sim.showTrafficLimit) {
                row3.add(SimLayoutComponent.Spacer(widthDp = WidgetDimens.SPACING_SMALL))
                row3.add(
                    SimLayoutComponent.Text(sim.trafficLimitText, sim.secondaryTextColor, WidgetDimens.FONT_LIMIT * sim.fontMultiplier),
                )
            }
            rows.add(SimLayoutRow(row3))
        }
        return rows
    }

    private fun createSixLinesRows(sim: SimUiState): List<SimLayoutRow> {
        val rows = mutableListOf<SimLayoutRow>()

        if (sim.showName) {
            rows.add(
                SimLayoutRow(
                    listOf(
                        SimLayoutComponent.Text(
                            sim.operatorName,
                            sim.textColor,
                            WidgetDimens.FONT_MULTI_NAME * sim.fontMultiplier,
                        ),
                    ),
                ),
            )
        }

        if (sim.showIcon) {
            rows.add(
                SimLayoutRow(
                    listOf(SimLayoutComponent.Icon(sim.operatorIconRes, (WidgetDimens.SIZE_ICON_MULTI * sim.fontMultiplier).toInt())),
                ),
            )
        }

        if (sim.showMinutes) {
            rows.add(
                SimLayoutRow(
                    listOf(
                        SimLayoutComponent.Text(
                            sim.minutesUsedFullText,
                            sim.textColor,
                            WidgetDimens.FONT_FULL * sim.fontMultiplier,
                        ),
                    ),
                ),
            )
            if (sim.showMinutesLimit) {
                rows.add(
                    SimLayoutRow(
                        listOf(
                            SimLayoutComponent.Text(
                                sim.minutesLimitFullText,
                                sim.secondaryTextColor,
                                WidgetDimens.FONT_FULL * sim.fontMultiplier,
                            ),
                        ),
                    ),
                )
            }
        }

        if (sim.showTraffic) {
            rows.add(
                SimLayoutRow(
                    listOf(
                        SimLayoutComponent.Text(
                            sim.trafficUsedFullText,
                            sim.textColor,
                            WidgetDimens.FONT_FULL * sim.fontMultiplier,
                        ),
                    ),
                ),
            )
            if (sim.showTrafficLimit) {
                rows.add(
                    SimLayoutRow(
                        listOf(
                            SimLayoutComponent.Text(
                                sim.trafficLimitFullText,
                                sim.secondaryTextColor,
                                WidgetDimens.FONT_FULL * sim.fontMultiplier,
                            ),
                        ),
                    ),
                )
            }
        }

        return rows
    }
}
