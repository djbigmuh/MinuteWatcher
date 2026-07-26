package com.example.minutewatcher.core.model

data class AppSettings(
    val theme: AppTheme = AppTheme.FOLLOW_SYSTEM,
    val language: String? = null,
    val notifyLowMinutes: Boolean = true,
    val notifyLowTraffic: Boolean = true,
    val callTrackingEnabled: Boolean = true,
    val widgetLayoutMode: Int = 2,
    val widgetSimArrangement: Int = 0,
    val widgetTransparency: Int = 50,
    val widgetFontSize: Int = 2,
    val widgetBackgroundColor: Int = 0xFF000000.toInt(),
    val showSim: List<Boolean> = listOf(true, true),
    val showSimIcon: List<Boolean> = listOf(true, true),
    val showSimName: List<Boolean> = listOf(true, true),
    val showMinutes: List<Boolean> = listOf(true, true),
    val showMinutesLimit: List<Boolean> = listOf(false, false),
    val showTraffic: List<Boolean> = listOf(true, true),
    val showTrafficLimit: List<Boolean> = listOf(false, false),
    val migrationV1Completed: Boolean = false,
)
