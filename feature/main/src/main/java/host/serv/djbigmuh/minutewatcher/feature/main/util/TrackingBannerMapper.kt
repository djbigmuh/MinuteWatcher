package host.serv.djbigmuh.minutewatcher.feature.main.util

import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.PermissionUiState
import host.serv.djbigmuh.minutewatcher.core.model.TrackingKey
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import host.serv.djbigmuh.minutewatcher.feature.main.TrackingBannerState

object TrackingBannerMapper {
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
        val simDiscovery = statusMap.getOrDefault(TrackingKey.SIM_DISCOVERY, TrackingStatus.Idle)

        val observationFailed = isObservationFailed(statusMap, isStorageDegraded)

        val phonePermissionState =
            determinePhonePermissionState(
                phoneGranted,
                settings.phonePermissionPermanentlyDenied && !phoneGranted,
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
                showSimDiscoveryErrorBanner = isSimDiscoveryError(simDiscovery),
                showCallsAttributionErrorBanner = isCallsAttributionError(calls),
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
            showSimDiscoveryErrorBanner = isSimDiscoveryError(simDiscovery),
            showCallsAttributionErrorBanner = isCallsAttributionError(calls),
        )
    }

    private fun isObservationFailed(
        statusMap: Map<TrackingKey, TrackingStatus>,
        isStorageDegraded: Boolean,
    ): Boolean {
        if (isStorageDegraded) return true

        val widgetError = statusMap[TrackingKey.WIDGET_OBSERVATION] as? TrackingStatus.Error
        val settingsError = statusMap[TrackingKey.APP_SETTINGS] as? TrackingStatus.Error
        val usageError = statusMap[TrackingKey.USAGE_STATE] as? TrackingStatus.Error

        val isWidgetObservationFailed =
            widgetError?.type == TrackingStatus.ErrorType.DATABASE_ERROR &&
                widgetError.code != TrackingStatus.ErrorCode.WIDGET_SIZE_LIMIT

        val isSettingsFailed =
            settingsError?.type == TrackingStatus.ErrorType.DATABASE_ERROR

        val isUsageFailed =
            usageError?.type == TrackingStatus.ErrorType.DATABASE_ERROR

        return isWidgetObservationFailed || isSettingsFailed || isUsageFailed
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
            settings.notificationPermissionPermanentlyDenied -> PermissionUiState.PERMANENTLY_DENIED
            else -> PermissionUiState.NOT_REQUESTED
        }
    }

    private fun isTrafficError(status: TrackingStatus?): Boolean =
        (status is TrackingStatus.Error) &&
            (status.type != TrackingStatus.ErrorType.PERMISSION_MISSING) &&
            (status.type != TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)

    private fun isCallsError(status: TrackingStatus?): Boolean =
        (status is TrackingStatus.Error) &&
            (status.type != TrackingStatus.ErrorType.PERMISSION_MISSING) &&
            (status.type != TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)

    private fun isCallsAttributionError(status: TrackingStatus?): Boolean =
        (status is TrackingStatus.Error) &&
            (status.type == TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)

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

    private fun isSimDiscoveryError(status: TrackingStatus?): Boolean =
        (status is TrackingStatus.Error) &&
            (status.type != TrackingStatus.ErrorType.PERMISSION_MISSING) &&
            (status.type != TrackingStatus.ErrorType.NOT_SUPPORTED)
}
