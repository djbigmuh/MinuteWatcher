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
    val showSim: Boolean = true,
    val showIcon: Boolean = true,
    val showName: Boolean = true,
    val showMinutes: Boolean = true,
    val showMinutesLimit: Boolean = false,
    val showTraffic: Boolean = true,
    val showTrafficLimit: Boolean = false,
)
