package com.example.minutewatcher.core.model

data class WidgetSettingsState(
    val layoutMode: Int = 2,
    val simArrangement: Int = 0,
    val transparency: Int = 50,
    val fontSize: Int = 2,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val notifyLowMinutes: Boolean = true,
    val notifyLowTraffic: Boolean = true,
    val callTrackingEnabled: Boolean = true,
    val simStates: Map<Int, SimSettingsState> = emptyMap(),
    val widgetLimitExceeded: Boolean = false,
    val settingsLoadFailed: Boolean = false,
)

data class SimSettingsState(
    val showSim: Boolean = true,
    val showIcon: Boolean = true,
    val showName: Boolean = true,
    val showMinutes: Boolean = true,
    val showMinutesLimit: Boolean = false,
    val showTraffic: Boolean = true,
    val showTrafficLimit: Boolean = false,
)
