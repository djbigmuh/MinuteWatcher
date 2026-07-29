package com.example.minutewatcher.core.model

data class WidgetUiState(
    val layoutMode: Int,
    val simArrangement: Int,
    val backgroundColor: Int,
    val transparency: Int,
    val simUiStates: List<SimUiState>,
    val isHorizontal: Boolean = true,
    val showSeparator: Boolean = false,
)

data class SimUiState(
    val slot: Int,
    val operatorName: String,
    val operatorIconRes: Int,
    val showSim: Boolean,
    val showIcon: Boolean,
    val showName: Boolean,
    val showMinutes: Boolean,
    val minutesText: String,
    val minutesColor: Int,
    val showMinutesLimit: Boolean,
    val minutesLimitText: String,
    val showTraffic: Boolean,
    val trafficText: String,
    val trafficColor: Int,
    val showTrafficLimit: Boolean,
    val trafficLimitText: String,
    val fontMultiplier: Float,
    val textColor: Int,
    val secondaryTextColor: Int,
    val hasMinutesData: Boolean = true,
    val hasTrafficData: Boolean = true,
    // Detailed fields for SIX_LINES mode
    val minutesUsedFullText: String = "",
    val minutesLimitFullText: String = "",
    val trafficUsedFullText: String = "",
    val trafficLimitFullText: String = "",
    val layoutSpec: SimLayoutSpec = SimLayoutSpec(emptyList()),
)

data class SimLayoutSpec(
    val rows: List<SimLayoutRow>,
)

data class SimLayoutRow(
    val components: List<SimLayoutComponent>,
)

sealed class SimLayoutComponent {
    data class Icon(
        val iconRes: Int,
        val sizeDp: Int,
    ) : SimLayoutComponent()

    data class Text(
        val text: String,
        val color: Int,
        val fontSizeSp: Float,
        val isBold: Boolean = false,
    ) : SimLayoutComponent()

    data class Spacer(
        val widthDp: Int = 0,
        val heightDp: Int = 0,
    ) : SimLayoutComponent()
}
