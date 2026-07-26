package com.example.minutewatcher.feature.widget.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetLayoutMode

class SimViewFactory(
    private val context: Context,
) {
    fun createSimView(
        simState: SimUiState,
        layoutModeValue: Int,
    ): View {
        val container =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
            }

        val fontMultiplier = simState.fontMultiplier
        val textColor = simState.textColor
        val secondaryColor = simState.secondaryTextColor

        when (WidgetLayoutMode.fromValue(layoutModeValue)) {
            WidgetLayoutMode.SINGLE_LINE -> setupSingleLine(container, simState, fontMultiplier, textColor, secondaryColor)
            WidgetLayoutMode.TWO_LINES -> setupTwoLines(container, simState, fontMultiplier, textColor, secondaryColor)
            WidgetLayoutMode.THREE_LINES -> setupThreeLines(container, simState, fontMultiplier, textColor, secondaryColor)
            WidgetLayoutMode.SIX_LINES -> setupSixLines(container, simState, fontMultiplier, textColor, secondaryColor)
        }

        return container
    }

    private fun setupSingleLine(
        container: LinearLayout,
        simState: SimUiState,
        fontMultiplier: Float,
        textColor: Int,
        secondaryColor: Int,
    ) {
        container.orientation = LinearLayout.HORIZONTAL
        if (simState.showIcon) container.addView(createIcon(context, simState))
        if (simState.showName) {
            container.addView(
                createName(
                    context,
                    simState,
                    textColor,
                    Constants.Widget.FONT_SIZE_SMALL * fontMultiplier,
                ).apply {
                    setMargins(context, 4, 0, 0, 0)
                },
            )
        }

        if (simState.showMinutes) {
            container.addView(
                createValueText(
                    context,
                    simState.minutesText,
                    simState.minutesColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    true,
                ).apply { setMargins(context, 4, 0, 0, 0) },
            )
            if (simState.showMinutesLimit) {
                container.addView(createValueText(context, simState.minutesLimitText, secondaryColor, 10f * fontMultiplier, false))
            }
            container.addView(
                createValueText(
                    context,
                    context.getString(R.string.minutes_abbr),
                    secondaryColor,
                    10f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
        }

        if (simState.showTraffic) {
            if (simState.showTrafficLimit) {
                container.addView(
                    createValueText(context, simState.trafficLimitText, secondaryColor, 8f * fontMultiplier, false).apply {
                        setMargins(context, 8, 0, 0, 0)
                    },
                )
            }
            container.addView(
                createValueText(
                    context,
                    simState.trafficText,
                    simState.trafficColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    false,
                ).apply { if (!simState.showTrafficLimit) setMargins(context, 8, 0, 0, 0) },
            )
            container.addView(
                createValueText(
                    context,
                    context.getString(R.string.gb_abbr),
                    secondaryColor,
                    10f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
        }
    }

    private fun setupTwoLines(
        container: LinearLayout,
        simState: SimUiState,
        fontMultiplier: Float,
        textColor: Int,
        secondaryColor: Int,
    ) {
        container.orientation = LinearLayout.HORIZONTAL
        val colHeader = createVerticalStack(context)
        if (simState.showIcon) colHeader.addView(createIcon(context, simState))
        if (simState.showName) colHeader.addView(createName(context, simState, textColor, 10f * fontMultiplier))
        container.addView(colHeader)

        if (simState.showMinutes) {
            val colMinutes = createVerticalStack(context).apply { setMargins(context, 8, 0, 0, 0) }
            colMinutes.addView(
                createValueText(
                    context,
                    simState.minutesText,
                    simState.minutesColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    true,
                ),
            )
            if (simState.showMinutesLimit) {
                colMinutes.addView(
                    createValueText(
                        context,
                        simState.minutesLimitText.removePrefix("/"),
                        secondaryColor,
                        10f * fontMultiplier,
                        false,
                    ),
                )
            }
            container.addView(colMinutes)
            container.addView(
                createValueText(
                    context,
                    context.getString(R.string.minutes_abbr),
                    secondaryColor,
                    10f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
        }

        if (simState.showTraffic) {
            val colTraffic = createVerticalStack(context).apply { setMargins(context, 12, 0, 0, 0) }
            colTraffic.addView(
                createValueText(
                    context,
                    simState.trafficText,
                    simState.trafficColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    false,
                ),
            )
            if (simState.showTrafficLimit) {
                colTraffic.addView(
                    createValueText(context, simState.trafficLimitText.removePrefix("/"), secondaryColor, 8f * fontMultiplier, false),
                )
            }
            container.addView(colTraffic)
            container.addView(
                createValueText(
                    context,
                    context.getString(R.string.gb_abbr),
                    secondaryColor,
                    10f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
        }
    }

    private fun setupThreeLines(
        container: LinearLayout,
        simState: SimUiState,
        fontMultiplier: Float,
        textColor: Int,
        secondaryColor: Int,
    ) {
        container.orientation = LinearLayout.VERTICAL
        val headerRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        if (simState.showIcon) headerRow.addView(createIcon(context, simState))
        if (simState.showName) {
            headerRow.addView(
                createName(
                    context,
                    simState,
                    textColor,
                    Constants.Widget.FONT_SIZE_SMALL * fontMultiplier,
                ).apply {
                    if (simState.showIcon) setMargins(context, 4, 0, 0, 0)
                },
            )
        }
        container.addView(headerRow)

        if (simState.showMinutes) {
            val minutesRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setMargins(context, 0, 2, 0, 0)
                }
            minutesRow.addView(
                createValueText(
                    context,
                    simState.minutesText,
                    simState.minutesColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    true,
                ),
            )
            if (simState.showMinutesLimit) {
                minutesRow.addView(
                    createValueText(context, simState.minutesLimitText, secondaryColor, 12f * fontMultiplier, false).apply {
                        setMargins(context, 2, 0, 0, 0)
                    },
                )
            }
            minutesRow.addView(
                createValueText(
                    context,
                    context.getString(R.string.minutes_abbr),
                    secondaryColor,
                    12f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
            container.addView(minutesRow)
        }

        if (simState.showTraffic) {
            val trafficRow =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setMargins(context, 0, 2, 0, 0)
                }
            trafficRow.addView(
                createValueText(
                    context,
                    simState.trafficText,
                    simState.trafficColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    false,
                ),
            )
            if (simState.showTrafficLimit) {
                trafficRow.addView(
                    createValueText(context, simState.trafficLimitText, secondaryColor, 10f * fontMultiplier, false).apply {
                        setMargins(context, 2, 0, 0, 0)
                    },
                )
            }
            trafficRow.addView(
                createValueText(
                    context,
                    context.getString(R.string.gb_abbr),
                    secondaryColor,
                    12f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
            container.addView(trafficRow)
        }
    }

    private fun setupSixLines(
        container: LinearLayout,
        simState: SimUiState,
        fontMultiplier: Float,
        textColor: Int,
        secondaryColor: Int,
    ) {
        container.orientation = LinearLayout.VERTICAL
        if (simState.showIcon) container.addView(createIcon(context, simState))
        if (simState.showName) container.addView(createName(context, simState, textColor, 10f * fontMultiplier))

        if (simState.showMinutes) {
            val minutesSection =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setMargins(context, 0, 2, 0, 0)
                }
            val valStack = createVerticalStack(context)
            valStack.addView(
                createValueText(
                    context,
                    simState.minutesText,
                    simState.minutesColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    true,
                ),
            )
            if (simState.showMinutesLimit) {
                valStack.addView(
                    createValueText(context, simState.minutesLimitText.removePrefix("/"), secondaryColor, 10f * fontMultiplier, false),
                )
            }
            minutesSection.addView(valStack)
            minutesSection.addView(
                createValueText(
                    context,
                    context.getString(R.string.minutes_abbr),
                    secondaryColor,
                    10f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
            container.addView(minutesSection)
        }

        if (simState.showTraffic) {
            val trafficSection =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setMargins(context, 0, 2, 0, 0)
                }
            val valStack = createVerticalStack(context)
            valStack.addView(
                createValueText(
                    context,
                    simState.trafficText,
                    simState.trafficColor,
                    Constants.Widget.FONT_SIZE_MEDIUM * fontMultiplier,
                    false,
                ),
            )
            if (simState.showTrafficLimit) {
                valStack.addView(
                    createValueText(context, simState.trafficLimitText.removePrefix("/"), secondaryColor, 8f * fontMultiplier, false),
                )
            }
            trafficSection.addView(valStack)
            trafficSection.addView(
                createValueText(
                    context,
                    context.getString(R.string.gb_abbr),
                    secondaryColor,
                    10f * fontMultiplier,
                    false,
                ).apply {
                    setMargins(context, 2, 0, 0, 0)
                },
            )
            container.addView(trafficSection)
        }
    }
}
