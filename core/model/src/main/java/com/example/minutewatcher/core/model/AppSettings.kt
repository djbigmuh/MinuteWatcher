package com.example.minutewatcher.core.model

data class AppSettings(
    val theme: AppTheme = AppTheme.FOLLOW_SYSTEM,
    val language: String? = null,
    val notifyLowMinutes: Boolean = true,
    val notifyLowTraffic: Boolean = true,
    val widgetLayoutMode: Int = Constants.Widget.DEFAULT_LAYOUT_MODE,
    val widgetSimArrangement: Int = Constants.Widget.SIM_ARRANGEMENT_HORIZONTAL,
    val widgetTransparency: Int = Constants.Widget.DEFAULT_TRANSPARENCY,
    val widgetFontSize: Int = Constants.Widget.DEFAULT_FONT_SIZE_INDEX,
    val widgetBackgroundColor: Int = Constants.UI.DEFAULT_BG_COLOR,
    val migrationV1Completed: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val notificationPermissionRequested: Boolean = false,
    val wasPhonePermissionRequestedBefore: Boolean = false,
    val benchmarkSimCount: Int = 0,
    val widgetLimitExceeded: Boolean = false,
    val inaccurateCallAttributionsCount: Int = 0,
    val callTrackingEnabled: Boolean = true,
    /**
     * Whether the settings have been successfully loaded from persistent storage.
     * Default state is not loaded.
     */
    val isLoaded: Boolean = false,
)
