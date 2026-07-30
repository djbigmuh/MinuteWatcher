package com.example.minutewatcher.core.model

data class WidgetSettingsState(
    val layoutMode: Int,
    val simArrangement: Int,
    val transparency: Int,
    val fontSize: Int,
    val backgroundColor: Int,
    val notifyLowMinutes: Boolean,
    val notifyLowTraffic: Boolean,
    val callTrackingEnabled: Boolean,
    val simStates: Map<Int, SimSettingsState>,
)

data class SimSettingsState(
    val showSim: Boolean,
    val showIcon: Boolean,
    val showName: Boolean,
    val showMinutes: Boolean,
    val showMinutesLimit: Boolean,
    val showTraffic: Boolean,
    val showTrafficLimit: Boolean,
)
