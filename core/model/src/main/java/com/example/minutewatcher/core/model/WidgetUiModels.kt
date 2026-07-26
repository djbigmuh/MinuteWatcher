package com.example.minutewatcher.core.model

data class WidgetConfig(
    val layoutMode: Int,
    val simArrangement: Int,
    val fontSize: Int,
    val transparency: Int,
    val backgroundColor: Int,
    val simConfigs: List<SimWidgetConfig>
)

data class SimWidgetConfig(
    val slot: Int,
    val showSim: Boolean,
    val showIcon: Boolean,
    val showName: Boolean,
    val showMinutes: Boolean,
    val showMinutesLimit: Boolean,
    val showTraffic: Boolean,
    val showTrafficLimit: Boolean
)

data class WidgetUiState(
    val layoutMode: Int,
    val simArrangement: Int,
    val backgroundColor: Int,
    val transparency: Int,
    val simUiStates: List<SimUiState>
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
    val secondaryTextColor: Int
)
