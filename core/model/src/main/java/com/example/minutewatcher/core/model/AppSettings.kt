package com.example.minutewatcher.core.model

/**
 * Global application settings state.
 *
 * @property theme App theme selection.
 * @property language Selected app language override, or null for system default.
 * @property notifyLowMinutes Whether low minutes notifications are enabled.
 * @property notifyLowTraffic Whether low traffic notifications are enabled.
 * @property widgetLayoutMode Widget layout mode setting.
 * @property widgetSimArrangement SIM arrangement mode for widget.
 * @property widgetTransparency Widget background transparency value (0-100).
 * @property widgetFontSize Widget font size index.
 * @property widgetBackgroundColor Widget background color value.
 * @property migrationV1Completed Whether V1 storage migration has completed.
 * @property onboardingCompleted Whether user onboarding has completed.
 * @property notificationPermissionRequested Whether notification permission has been requested.
 * @property wasPhonePermissionRequestedBefore Whether phone permission has been requested before.
 * @property phonePermissionPermanentlyDenied Whether phone permission is permanently denied.
 * @property notificationPermissionPermanentlyDenied Whether notification permission is permanently denied.
 * @property benchmarkSimCount Benchmark SIM count for testing.
 * @property widgetLimitExceeded Widget slot limit exceeded flag.
 * @property inaccurateCallAttributionsCount Counter for fallback attribution events.
 * @property callTrackingEnabled Call minutes tracking enabled flag.
 * @property assistantAccessEnabled User consent for on-device assistant access via AppFunctions.
 * @property aodSummaryEnabled Always-on Display summary notification enabled flag.
 * @property isLoaded Whether settings have been successfully loaded from persistent storage.
 */
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
    val phonePermissionPermanentlyDenied: Boolean = false,
    val notificationPermissionPermanentlyDenied: Boolean = false,
    val benchmarkSimCount: Int = 0,
    val widgetLimitExceeded: Boolean = false,
    /**
     * Counter for fallback attribution events (Situation A). Counts situation A only;
     * reset by user action in UI and not increased during ambiguous attribution (Situation B).
     */
    val inaccurateCallAttributionsCount: Int = 0,
    val callTrackingEnabled: Boolean = true,
    /**
     * User consent for on-device assistant to request remaining minutes and data via AppFunctions (Android 16+).
     * Disabled by default.
     */
    val assistantAccessEnabled: Boolean = false,
    /**
     * Whether the persistent silent summary notification for lock screen and Always-on Display is enabled.
     * Display on Always-on Display depends on device capabilities.
     */
    val aodSummaryEnabled: Boolean = false,
    /**
     * Whether the settings have been successfully loaded from persistent storage.
     * Default state is not loaded.
     */
    val isLoaded: Boolean = false,
)
