package com.example.minutewatcher.core.model

data class WidgetSettingsState(
    val layoutMode: Int = 2,
    val simArrangement: Int = 0,
    val transparency: Int = 50,
    val fontSize: Int = 2,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val notifyLowMinutes: Boolean = true,
    val notifyLowTraffic: Boolean = true,
    val simStates: Map<Int, SimSettingsState> = emptyMap(),
    val widgetLimitExceeded: Boolean = false,
    /**
     * Whether the general app settings (layout, transparency, etc.) failed to load.
     * If this is true, the entire widget settings UI should typically show an error.
     */
    val settingsLoadFailed: Boolean = false,
    /**
     * Whether the active SIM list or their specific settings failed to load due to a timeout.
     * General settings can still be valid and savable if this is true.
     */
    val simListLoadFailed: Boolean = false,
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
