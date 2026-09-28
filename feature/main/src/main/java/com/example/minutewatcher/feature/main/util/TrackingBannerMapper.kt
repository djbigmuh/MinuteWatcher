package com.example.minutewatcher.feature.main.util

import com.example.minutewatcher.core.model.AppSettings
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.PermissionUiState
import com.example.minutewatcher.core.model.TrackingKey
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.feature.main.TrackingBannerState

object TrackingBannerMapper {
    @Suppress("LongParameterList")
    fun mapToBannerState(
        statusMap: Map<TrackingKey, TrackingStatus>,
        phoneGranted: Boolean,
        usageGranted: Boolean,
        notificationsGranted: Boolean,
        settings: AppSettings,
        appSearchRetryCount: Int = 0,
        appSearchBannerDismissed: Boolean = false,
        isStorageDegraded: Boolean = false,
    ): TrackingBannerState {
        val traffic = statusMap.getOrDefault(TrackingKey.TRAFFIC, TrackingStatus.Idle)
        val appsearch = statusMap.getOrDefault(TrackingKey.APPSEARCH, TrackingStatus.Idle)
        val shortcuts = statusMap.getOrDefault(TrackingKey.SHORTCUTS, TrackingStatus.Idle)
        val calls = statusMap.getOrDefault(TrackingKey.CALLS, TrackingStatus.Idle)

        val observationFailed = isObservationFailed(statusMap, isStorageDegraded)

        val phonePermissionState =
            determinePhonePermissionState(
                phoneGranted,
                settings.wasPhonePermissionRequestedBefore && !phoneGranted,
            )

        val notificationPermissionState =
            determineNotificationPermissionState(
                notificationsGranted,
                settings,
            )

        if (!settings.onboardingCompleted) {
            return TrackingBannerState(
                phonePermissionState = phonePermissionState,
                notificationPermissionState = notificationPermissionState,
                notificationsGrantedActual = notificationsGranted,
                showTrafficErrorBanner = isTrafficError(traffic),
                showCallsErrorBanner = isCallsError(calls),
                observationFailed = observationFailed,
                isStorageDegraded = isStorageDegraded,
            )
        }

        return TrackingBannerState(
            phonePermissionState = phonePermissionState,
            showUsageAccessBanner = !usageGranted,
            showTrafficErrorBanner = isTrafficError(traffic),
            showCallsErrorBanner = isCallsError(calls),
            showAppSearchErrorBanner =
                isAppSearchErrorToShow(
                    appsearch,
                    appSearchRetryCount,
                    appSearchBannerDismissed,
                ),
            appSearchErrorType = (appsearch as? TrackingStatus.Error)?.type,
            appSearchBannerDismissed = appSearchBannerDismissed,
            showShortcutsErrorBanner = isShortcutsError(shortcuts),
            showWidgetErrorBanner = settings.widgetLimitExceeded,
            showInaccurateCallsBanner =
                settings.inaccurateCallAttributionsCount >=
                    Constants.Calls.INACCURATE_ATTRIBUTION_BANNER_THRESHOLD,
            notificationPermissionState = notificationPermissionState,
            notificationsGrantedActual = notificationsGranted,
            observationFailed = observationFailed,
            isStorageDegraded = isStorageDegraded,
        )
    }

    private fun isObservationFailed(
        statusMap: Map<TrackingKey, TrackingStatus>,
        isStorageDegraded: Boolean,
    ): Boolean {
        val widgetError =
            statusMap.getOrDefault(TrackingKey.WIDGET_OBSERVATION, TrackingStatus.Idle) as? TrackingStatus.Error
        val settingsError =
            statusMap.getOrDefault(TrackingKey.APP_SETTINGS, TrackingStatus.Idle) as? TrackingStatus.Error
        val usageError =
            statusMap.getOrDefault(TrackingKey.USAGE_STATE, TrackingStatus.Idle) as? TrackingStatus.Error

        val isDatabaseError =
            widgetError?.type == TrackingStatus.ErrorType.DATABASE_ERROR ||
                settingsError?.type == TrackingStatus.ErrorType.DATABASE_ERROR ||
                usageError?.type == TrackingStatus.ErrorType.DATABASE_ERROR

        val isSizeLimitError =
            widgetError?.code == TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT ||
                widgetError?.type == TrackingStatus.ErrorType.NOT_SUPPORTED
        return (isDatabaseError && !isSizeLimitError) || isStorageDegraded
    }

    private fun determinePhonePermissionState(
        granted: Boolean,
        permanentlyDenied: Boolean,
    ): PermissionUiState =
        when {
            granted -> PermissionUiState.GRANTED
            permanentlyDenied -> PermissionUiState.PERMANENTLY_DENIED
            else -> PermissionUiState.NOT_REQUESTED
        }

    private fun determineNotificationPermissionState(
        granted: Boolean,
        settings: AppSettings,
    ): PermissionUiState {
        val showNotifications = settings.notifyLowMinutes || settings.notifyLowTraffic
        return when {
            granted -> PermissionUiState.GRANTED
            !showNotifications -> PermissionUiState.GRANTED
            settings.notificationPermissionRequested -> PermissionUiState.PERMANENTLY_DENIED
            else -> PermissionUiState.NOT_REQUESTED
        }
    }

    private fun isTrafficError(status: TrackingStatus?): Boolean =
        (status is TrackingStatus.Error) &&
            (status.type != TrackingStatus.ErrorType.PERMISSION_MISSING) &&
            (status.type != TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)

    private fun isCallsError(status: TrackingStatus?): Boolean =
        (status is TrackingStatus.Error) &&
            (status.type != TrackingStatus.ErrorType.PERMISSION_MISSING)

    private fun isAppSearchErrorToShow(
        status: TrackingStatus?,
        retryCount: Int,
        dismissed: Boolean,
    ): Boolean {
        if ((status !is TrackingStatus.Error) || dismissed) return false

        return when (status.type) {
            TrackingStatus.ErrorType.NOT_SUPPORTED -> false
            TrackingStatus.ErrorType.DATABASE_ERROR -> retryCount < MAX_APPSEARCH_RETRY_COUNT
            else -> true
        }
    }

    /**
     * Maximum number of user-initiated AppSearch recovery attempts before the error banner is hidden.
     * The counter increments on each retry request from the UI. After reaching the limit, the banner
     * is hidden to avoid annoying the user with an unrecoverable system error.
     *
     * The counter is reset when the AppSearch error type changes and upon app restart.
     */
    private const val MAX_APPSEARCH_RETRY_COUNT = 3

    private fun isShortcutsError(status: TrackingStatus?): Boolean =
        (status is TrackingStatus.Error) &&
            (status.type != TrackingStatus.ErrorType.NOT_SUPPORTED)
}
