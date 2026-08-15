package com.example.minutewatcher.core.model

data class AppSettings(
    val theme: AppTheme = AppTheme.FOLLOW_SYSTEM,
    val language: String? = null,
    val notifyLowMinutes: Boolean = true,
    val notifyLowTraffic: Boolean = true,
    val widgetLayoutMode: Int = 2,
    val widgetSimArrangement: Int = 0,
    val widgetTransparency: Int = 50,
    val widgetFontSize: Int = 2,
    val widgetBackgroundColor: Int = Constants.UI.DEFAULT_BG_COLOR,
    val migrationV1Completed: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val notificationPermissionRequested: Boolean = false,
    val phonePermissionRequested: Boolean = false,
    val benchmarkSimCount: Int = 0,
    val widgetLimitExceeded: Boolean = false,
    val inaccurateCallAttributionsCount: Int = 0,
)
