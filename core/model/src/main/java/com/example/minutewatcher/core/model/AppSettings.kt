package com.example.minutewatcher.core.model

data class AppSettings(
    val theme: AppTheme,
    val language: String?,
    val notifyLowMinutes: Boolean,
    val notifyLowTraffic: Boolean,
    val callTrackingEnabled: Boolean,
    val widgetLayoutMode: Int,
    val widgetSimArrangement: Int,
    val widgetTransparency: Int,
    val widgetFontSize: Int,
    val widgetBackgroundColor: Int,
    val showSim: List<Boolean>,
    val showSimIcon: List<Boolean>,
    val showSimName: List<Boolean>,
    val showMinutes: List<Boolean>,
    val showMinutesLimit: List<Boolean>,
    val showTraffic: List<Boolean>,
    val showTrafficLimit: List<Boolean>,
    val migrationV1Completed: Boolean,
)
